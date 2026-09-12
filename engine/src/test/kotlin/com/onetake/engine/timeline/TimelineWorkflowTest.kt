package com.onetake.engine.timeline

import com.onetake.engine.Cut
import com.onetake.engine.EditPlan
import com.onetake.engine.Timeline as SampleClock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the shared analysis -> editor -> persisted export contract. */
class TimelineWorkflowTest {
    @Test fun removingMiddleTwoSecondsExportsFourSecondsWithoutChangingSourcePlan() {
        val clock = SampleClock()
        val plan = EditPlan(clock.samplesFromMillis(6_000), listOf(
            Cut("pause", clock.samplesFromMillis(2_000), clock.samplesFromMillis(4_000), "silence"),
        ))
        val timeline = Timeline.fromEditPlan(plan)
        val history = History(timeline)
        val restored = history.apply(timeline.restore("pause"))
        assertEquals(6_000L, clock.msFromSamples(restored.current.exportedDurationSamples()))
        assertEquals(timeline, restored.undo().current)

        val persisted = TimelineCodec.decode(TimelineCodec.encode(restored.undo().current))
        assertEquals(4_000L, clock.msFromSamples(persisted.exportedDurationSamples()))
        assertEquals(plan.keptRanges().map { it.startSample to it.endSample },
            persisted.keptClips().map { it.sourceStart to it.sourceEnd })
        assertEquals(true, plan.cuts.single().enabled)
        assertEquals(96_000L, plan.durationSamples)
    }

    @Test fun reorderedClipsSurviveSerializationAndExactUndoRedo() {
        val initial = Timeline.fromAnalysis(60, listOf(RemovalCandidate(20, 40, "pause")))
        val last = initial.clips.last()
        val moved = initial.reorder(last.id, 0)
        val history = History(initial).apply(moved).apply(moved.delete(last.id))
        assertEquals(listOf(40L, 0L, 20L), moved.clips.map { it.sourceStart })
        assertEquals(moved, history.undo().current)
        assertEquals(initial, history.undo().undo().current)
        assertEquals(history.current, history.undo().undo().redo().redo().current)
        val saved = TimelineCodec.decode(TimelineCodec.encode(history.current))
        assertEquals(history.current, saved)
        assertEquals(20L, saved.exportedDurationSamples())
    }
}
