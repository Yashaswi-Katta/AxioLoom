package com.offline.inkmath.domain.clustering

import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.StrokeGeometry
import com.offline.inkmath.domain.model.TimedStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorkspaceHistoryTest {

    private fun createStroke(id: UUID = UUID.randomUUID()): TimedStroke {
        val geom = StrokeGeometry(
            id = id,
            points = listOf(InkPoint(0f, 0f, 0L), InkPoint(10f, 10f, 50L)),
            bounds = Rect(0f, 0f, 10f, 10f)
        )
        return TimedStroke.forTesting(geom)
    }

    private fun createBlock(
        id: UUID = UUID.randomUUID(),
        revision: Long = 0L,
        stroke: TimedStroke = createStroke()
    ): EquationBlock {
        return EquationBlock(
            id = id,
            strokes = listOf(stroke),
            bounds = stroke.bounds,
            medianStrokeHeight = 10f,
            resultAnchorPoint = null,
            revision = revision
        )
    }

    @Test
    fun initialStateHasNoUndoOrRedo() {
        val manager = WorkspaceHistoryManager(maxDepth = 50)
        assertFalse(manager.canUndo)
        assertFalse(manager.canRedo)
        assertEquals(0, manager.undoCount)
        assertEquals(0, manager.redoCount)

        val emptyState = InkWorkspaceState()
        assertNull(manager.undo(emptyState))
        assertNull(manager.redo(emptyState))
    }

    @Test
    fun recordCommitEnablesUndoAndTransitionsCorrectly() {
        val manager = WorkspaceHistoryManager(maxDepth = 50)
        val stroke1 = createStroke()
        val block1 = createBlock(stroke = stroke1)
        val state1 = InkWorkspaceState(strokes = listOf(stroke1), blocks = listOf(block1), version = 1L)

        val stroke2 = createStroke()
        val block2 = createBlock(stroke = stroke2)
        val state2 = InkWorkspaceState(strokes = listOf(stroke1, stroke2), blocks = listOf(block1, block2), version = 2L)

        manager.recordCommit(state1)
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
        assertEquals(1, manager.undoCount)

        val undoDiff = manager.undo(state2)
        assertNotNull(undoDiff)
        assertEquals(state1, undoDiff!!.targetState)
        assertFalse(manager.canUndo)
        assertTrue(manager.canRedo)
        assertEquals(1, manager.redoCount)

        val redoDiff = manager.redo(state1)
        assertNotNull(redoDiff)
        assertEquals(state2, redoDiff!!.targetState)
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun diffCorrectlyIdentifiesRemovedAddedAndUnchangedBlocks() {
        val manager = WorkspaceHistoryManager(maxDepth = 50)

        val blockAId = UUID.randomUUID()
        val blockBId = UUID.randomUUID()
        val blockCId = UUID.randomUUID()

        val strokeA = createStroke()
        val strokeB = createStroke()
        val strokeC = createStroke()

        val blockA_v0 = createBlock(id = blockAId, revision = 0L, stroke = strokeA)
        val blockB_v0 = createBlock(id = blockBId, revision = 0L, stroke = strokeB)
        val state1 = InkWorkspaceState(
            strokes = listOf(strokeA, strokeB),
            blocks = listOf(blockA_v0, blockB_v0),
            version = 1L
        )

        // In state2: blockB was deleted, blockA updated to revision 1, blockC added
        val blockA_v1 = createBlock(id = blockAId, revision = 1L, stroke = strokeA)
        val blockC_v0 = createBlock(id = blockCId, revision = 0L, stroke = strokeC)
        val state2 = InkWorkspaceState(
            strokes = listOf(strokeA, strokeC),
            blocks = listOf(blockA_v1, blockC_v0),
            version = 2L
        )

        manager.recordCommit(state1)
        val undoDiff = manager.undo(state2)!!

        assertEquals(state1, undoDiff.targetState)
        // blockC was in state2, not in state1 -> removed
        assertEquals(setOf(blockCId), undoDiff.removedBlockIds)

        // blockA in state1 is rev 0 vs rev 1 in state2 -> changed
        // blockB in state1 was absent in state2 -> added
        val changedIds = undoDiff.addedOrChangedBlocks.map { it.id }.toSet()
        assertEquals(setOf(blockAId, blockBId), changedIds)
        assertTrue(undoDiff.unchangedRestoredBlocks.isEmpty())
    }

    @Test
    fun boundedStackDropsOldestWhenExceedingMaxDepth() {
        val manager = WorkspaceHistoryManager(maxDepth = 5)
        for (i in 1..8) {
            val s = createStroke()
            val b = createBlock(stroke = s)
            manager.recordCommit(InkWorkspaceState(strokes = listOf(s), blocks = listOf(b), version = i.toLong()))
        }
        assertEquals(5, manager.undoCount)
    }

    @Test
    fun clearResetsBothStacks() {
        val manager = WorkspaceHistoryManager(maxDepth = 50)
        val s = createStroke()
        val b = createBlock(stroke = s)
        manager.recordCommit(InkWorkspaceState(strokes = listOf(s), blocks = listOf(b)))
        assertTrue(manager.canUndo)

        manager.clear()
        assertFalse(manager.canUndo)
        assertFalse(manager.canRedo)
        assertEquals(0, manager.undoCount)
        assertEquals(0, manager.redoCount)
    }
}
