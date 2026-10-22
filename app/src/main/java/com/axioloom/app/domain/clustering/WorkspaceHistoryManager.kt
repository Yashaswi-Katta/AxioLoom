package com.axioloom.app.domain.clustering

import com.axioloom.app.domain.model.EquationBlock
import java.util.ArrayDeque
import java.util.UUID

data class WorkspaceDiff(
    val targetState: InkWorkspaceState,
    val removedBlockIds: Set<UUID>,
    val addedOrChangedBlocks: List<EquationBlock>,
    val unchangedRestoredBlocks: List<EquationBlock>
)

/**
 * Bounded undo/redo history manager for the ink workspace.
 * Computes block-level diffs based on immutable block tickets (id and revision).
 */
class WorkspaceHistoryManager(val maxDepth: Int = 50) {
    private val undoStack = ArrayDeque<InkWorkspaceState>()
    private val redoStack = ArrayDeque<InkWorkspaceState>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun recordCommit(previousState: InkWorkspaceState) {
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(previousState)
        redoStack.clear()
    }

    fun undo(currentState: InkWorkspaceState): WorkspaceDiff? {
        if (undoStack.isEmpty()) return null
        val targetState = undoStack.removeLast()
        if (redoStack.size >= maxDepth) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentState)
        return computeDiff(currentState, targetState)
    }

    fun redo(currentState: InkWorkspaceState): WorkspaceDiff? {
        if (redoStack.isEmpty()) return null
        val targetState = redoStack.removeLast()
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentState)
        return computeDiff(currentState, targetState)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun computeDiff(
        currentState: InkWorkspaceState,
        targetState: InkWorkspaceState
    ): WorkspaceDiff {
        val currentBlocks = currentState.blocks.associateBy { it.id }
        val targetBlocks = targetState.blocks.associateBy { it.id }
        val removedBlockIds = currentBlocks.keys - targetBlocks.keys

        val addedOrChanged = mutableListOf<EquationBlock>()
        val unchangedRestored = mutableListOf<EquationBlock>()

        for (block in targetState.blocks) {
            val current = currentBlocks[block.id]
            if (current == null || current.revision != block.revision) {
                addedOrChanged += block
            } else {
                unchangedRestored += block
            }
        }

        return WorkspaceDiff(
            targetState = targetState,
            removedBlockIds = removedBlockIds,
            addedOrChangedBlocks = addedOrChanged,
            unchangedRestoredBlocks = unchangedRestored
        )
    }
}
