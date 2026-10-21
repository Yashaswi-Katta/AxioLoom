package com.offline.inkmath.domain.clustering

import com.offline.inkmath.domain.gesture.ScratchDecision
import com.offline.inkmath.domain.gesture.ScratchOutDetector
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.TimedStroke
import java.util.UUID

/** Immutable input/output state; compute a complete next state before publishing it to Compose. */
data class InkWorkspaceState(
    val strokes: List<TimedStroke> = emptyList(),
    val blocks: List<EquationBlock> = emptyList(),
    val version: Long = 0L
) {
    init {
        require(version >= 0L)
        require(strokes.map { it.id }.distinct().size == strokes.size)
        require(blocks.map { it.id }.distinct().size == blocks.size)
    }
}

data class StrokeCommitResult(
    val state: InkWorkspaceState,
    val scratchDecision: ScratchDecision?,
    /** Previously published block IDs that must be removed from the DAG. */
    val removedBlockIds: Set<UUID>,
    /** Existing DAG entries whose stroke membership changed and must be replaced/invalidated. */
    val invalidatedBlockIds: Set<UUID>,
    /** New or changed block IDs that need a fresh recognition/debounce cycle. */
    val dirtyBlockIds: Set<UUID>
)

/**
 * Atomic stroke commit policy: a qualified, intersecting scratch stroke is consumed and excluded;
 * otherwise the stroke is added. Then all surviving strokes are reclustered against previous block
 * identities. Callers can apply returned DAG removals, invalidations and dirty IDs as one update.
 */
class InkWorkspaceReducer(
    private val detector: ScratchOutDetector = ScratchOutDetector(),
    private val grouper: SpatialBlockGrouper = SpatialBlockGrouper()
) {
    fun commit(state: InkWorkspaceState, finishedStroke: TimedStroke): StrokeCommitResult {
        require(state.strokes.none { it.id == finishedStroke.id }) { "Stroke ID already exists in workspace" }
        val decision = detector.detect(finishedStroke, state.strokes)
        val scratch = decision.takeIf { it.isScratchOut }
        val erased = scratch?.intersectedStrokes?.mapTo(HashSet()) { it.id }.orEmpty()
        val nextStrokes = if (scratch != null) state.strokes.filterNot { it.id in erased } else state.strokes + finishedStroke
        val affectedOld = if (scratch == null) emptySet() else state.blocks.filter { block -> block.strokeIds.any(erased::contains) }.mapTo(linkedSetOf()) { it.id }
        val nextBlocks = grouper.group(nextStrokes, state.blocks)
        val nextIds = nextBlocks.mapTo(HashSet()) { it.id }
        val oldById = state.blocks.associateBy { it.id }
        val changed = nextBlocks.filter { next ->
            val old = oldById[next.id]
            old == null || old.revision != next.revision
        }.mapTo(linkedSetOf()) { it.id }
        val removed = state.blocks.mapNotNullTo(linkedSetOf()) { old -> old.id.takeIf { it !in nextIds } }
        val invalidated = affectedOld.filterTo(linkedSetOf()) { it in nextIds }
        return StrokeCommitResult(
            state = InkWorkspaceState(nextStrokes.toList(), nextBlocks.toList(), state.version + 1),
            scratchDecision = decision,
            removedBlockIds = removed,
            invalidatedBlockIds = invalidated,
            dirtyBlockIds = changed
        )
    }
}
