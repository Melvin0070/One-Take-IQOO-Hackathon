package com.example.one_take

import com.example.one_take.captions.CaptionSegment
import com.onetake.engine.*
import org.junit.Assert.*
import org.junit.Test

class ScriptCaptureControllerTest {
    @Test fun committedSnapshotsAreDeduplicatedButLaterRepetitionsRemainVisible() {
        val changes = mutableListOf<Change>()
        val script = "Today I am going to show you how One Take works."
        val controller = ScriptCaptureController(script, RecordingSession { _, change, _ -> changes += change })
        val first = CaptionSegment(0, 1000, "Today I'm going to show you how the One Take app works.")
        controller.consume(listOf(first))
        controller.consume(listOf(first))
        assertEquals(1, controller.progress.chunks.single().attempts)
        controller.consume(listOf(first, first.copy(startMs = 1000, endMs = 2000)))
        assertEquals(2, controller.progress.chunks.single().attempts)
        assertEquals(1, changes.filterIsInstance<Change.TakeAttemptObserved>().size)
    }
}
