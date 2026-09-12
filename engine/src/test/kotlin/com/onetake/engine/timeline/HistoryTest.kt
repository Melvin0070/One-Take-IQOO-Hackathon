package com.onetake.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
