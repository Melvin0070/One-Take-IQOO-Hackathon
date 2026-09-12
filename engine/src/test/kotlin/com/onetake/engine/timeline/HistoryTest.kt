package com.onetake.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test
    fun initialHistoryStartsAtTheSuppliedTimeline() {
        val timeline = Timeline(listOf(Clip("source", 0L, 16_000L)))

        val history = History(timeline)

        assertEquals(timeline, history.current)
        assertTrue(history.undoStack.isEmpty())
        assertTrue(history.redoStack.isEmpty())
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun applyingAndUndoingExposeImmutableHistorySnapshots() {
        val initial = Timeline(listOf(Clip("source", 0L, 16_000L)))
        val changed = Timeline(listOf(Clip("source", 1_000L, 16_000L)))

        val applied = History(initial).apply(changed)
        val undone = applied.undo()

        assertEquals(changed, applied.current)
        assertEquals(initial, undone.current)
        assertTrue(undone.canRedo)
    }

    @Test
    fun undoAndRedoPreserveTheirStackOrdering() {
        val first = Timeline(listOf(Clip("first", 0L, 10L)))
        val second = Timeline(listOf(Clip("second", 0L, 10L)))
        val third = Timeline(listOf(Clip("third", 0L, 10L)))

        val applied = History(first).apply(second).apply(third)
        val undoneOnce = applied.undo()
        val undoneTwice = undoneOnce.undo()
        val redoneOnce = undoneTwice.redo()
        val redoneTwice = redoneOnce.redo()

        assertEquals(third, applied.current)
        assertEquals(second, undoneOnce.current)
        assertEquals(first, undoneTwice.current)
        assertEquals(second, redoneOnce.current)
        assertEquals(third, redoneTwice.current)
        assertEquals(listOf(first, second), redoneTwice.undoStack)
        assertTrue(redoneTwice.redoStack.isEmpty())
    }

    @Test
    fun applyingAfterUndoClearsRedoAndApplyingTheCurrentTimelineDoesNot() {
        val first = Timeline(listOf(Clip("first", 0L, 10L)))
        val second = Timeline(listOf(Clip("second", 0L, 10L)))
        val third = Timeline(listOf(Clip("third", 0L, 10L)))

        val afterUndo = History(first).apply(second).apply(third).undo()
        val noOp = afterUndo.apply(second)

        assertEquals(afterUndo, noOp)
        assertTrue(noOp.canRedo)
        assertEquals(first, noOp.undo().current)
        assertEquals(third, noOp.redo().current)

        val branch = afterUndo.apply(first)
        assertFalse(branch.canRedo)
        assertEquals(first, branch.current)
    }

    @Test
    fun emptyUndoAndRedoAreNoOps() {
        val history = History(Timeline(listOf(Clip("source", 0L, 10L))))

        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
        assertSame(history, history.apply(history.current))
    }

    @Test
    fun historyStacksAreDefensiveAndUnmodifiable() {
        val first = Timeline(listOf(Clip("first", 0L, 10L)))
        val second = Timeline(listOf(Clip("second", 0L, 10L)))
        val suppliedUndo = mutableListOf(first)
        val suppliedRedo = mutableListOf(second)

        val history = History(second, suppliedUndo, suppliedRedo)
        suppliedUndo.clear()
        suppliedRedo.clear()

        assertEquals(listOf(first), history.undoStack)
        assertEquals(listOf(second), history.redoStack)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (history.undoStack as MutableList<Timeline>) += second
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (history.redoStack as MutableList<Timeline>) += first
        }
    }
}
