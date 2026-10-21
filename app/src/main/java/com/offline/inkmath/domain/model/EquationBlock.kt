package com.offline.inkmath.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.UUID

enum class BlockDisplayMode { HANDWRITTEN, TYPESET }

enum class SymbolicTransform {
    AUTO,
    SIMPLIFY,
    FACTOR,
    EXPAND,
    PARTIAL_FRACTIONS,
    TOGETHER,
    TRIG_EXPAND,
    TRIG_REDUCE,
    TAYLOR_SERIES
}

/** Reject recognition/CAS completions captured before an edit, split or merge. */
data class BlockRevision(val blockId: UUID, val revision: Long) {
    init { require(revision >= 0L) }
}

/**
 * A spatial expression, independent of any viewport and of an ONNX runtime/session.
 * Instances published by the grouper/workspace own unmodifiable collections.
 * A null anchor means no geometrically plausible terminal handwritten '=' was found.
 */
data class EquationBlock internal constructor(
    val id: UUID,
    val strokes: List<TimedStroke>,
    val bounds: Rect,
    val medianStrokeHeight: Float,
    val resultAnchorPoint: Offset?,
    val revision: Long = 0L,
    val recognizedLatex: String? = null,
    val intent: MathIntent? = null,
    val result: CasResult? = null,
    val status: BlockStatus = BlockStatus.Dirty,
    val solveTarget: String? = null,
    val candidateSolveVars: List<String> = emptyList(),
    val displayMode: BlockDisplayMode = BlockDisplayMode.HANDWRITTEN,
    val transform: SymbolicTransform = SymbolicTransform.AUTO,
    val alternatives: List<String> = emptyList()
) {
    init {
        require(strokes.isNotEmpty())
        require(strokes.map { it.id }.distinct().size == strokes.size)
        require(bounds.isFiniteOrdered())
        require(medianStrokeHeight.isFinite() && medianStrokeHeight > 0f)
        require(revision >= 0L)
        require(resultAnchorPoint == null || (resultAnchorPoint.x.isFinite() && resultAnchorPoint.y.isFinite()))
    }
    val strokeIds: Set<UUID> get() = strokes.mapTo(linkedSetOf()) { it.id }
    val ticket: BlockRevision get() = BlockRevision(id, revision)
    val needsRecognition: Boolean get() = recognizedLatex == null && status !is BlockStatus.Error
}
