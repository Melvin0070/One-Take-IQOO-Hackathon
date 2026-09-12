package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    @Test
    fun lateCaptionsAreAcceptedAfterFinalization() {
        val engine = EditingEngine("session")
        engine.submit(12_000L, sourceFinalized())

        val captions = listOf(Caption(1_000L, 2_000L, "hello"))
        engine.submit(2_000L, Change.CaptionsReplaced(captions))

        assertEquals(captions, engine.snapshot().captions)
        assertEquals(2L, engine.snapshot().revision)
    }

    @Test
    fun eventsAtTheSameSampleKeepArrivalOrder() {
        val events = mutableListOf<Event>()
        val engine = EditingEngine("session", EventSink { events += it })

        val first = engine.submit(0L, sourceFinalized())
        val second = engine.submit(0L, Change.CaptionsReplaced(emptyList()))

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals(listOf(0L, 0L), events.map { it.sample })
    }

    @Test
    fun lateEventsReplayToTheSameStateWithoutResorting() {
        val events = mutableListOf<Event>()
        val original = EditingEngine("session", EventSink { events += it })
        original.submit(12_000L, sourceFinalized())
        original.submit(
            7_000L,
            Change.EditsReplaced(
                EditPlan(
                    durationSamples = 20_000L,
                    cuts = listOf(Cut("pause", 5_000L, 7_000L, "silence")),
                ),
            ),
        )
        original.submit(
            2_000L,
            Change.CaptionsReplaced(listOf(Caption(0L, 5_000L, "before"))),
        )
        original.submit(1_000L, Change.CutToggled("pause"))

        val replayed = EditingEngine("session", history = events)

        assertEquals(original.snapshot(), replayed.snapshot())
        assertEquals(4L, replayed.snapshot().revision)
    }

    @Test
    fun sinkFailureDoesNotPublishStateOrConsumeSequence() {
        var shouldFail = true
        val sink = EventSink {
            if (shouldFail) error("disk unavailable")
        }
        val engine = EditingEngine("session", sink)

        assertThrows(IllegalStateException::class.java) {
            engine.submit(0L, sourceFinalized())
        }
        assertEquals(EngineState("session"), engine.snapshot())

        shouldFail = false
        val accepted = engine.submit(0L, sourceFinalized())
        assertEquals(1L, accepted.sequence)
        assertEquals(SessionPhase.READY, engine.snapshot().phase)
    }

    @Test
    fun invalidLifecycleAndDurationChangesAreRejected() {
        val engine = EditingEngine("session")
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(0L, Change.CaptionsReplaced(emptyList()))
        }

        engine.submit(0L, sourceFinalized())
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(0L, sourceFinalized())
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(20_001L, Change.CaptionsReplaced(emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(
                0L,
                Change.EditsReplaced(EditPlan(19_999L, emptyList())),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(-1L, Change.CaptionsReplaced(emptyList()))
        }
    }

    @Test
    fun editPlanMapsCaptionsAndPreservesSourceBoundaries() {
        val plan = EditPlan(
            durationSamples = 20_000L,
            cuts = listOf(
                Cut("first", 2_000L, 5_000L, "silence"),
                Cut("disabled", 8_000L, 10_000L, "manual", enabled = false),
            ),
        )

        assertEquals(
            listOf(
                SampleRange(0L, 2_000L),
                SampleRange(5_000L, 20_000L),
            ),
            plan.keptRanges(),
        )
        assertEquals(0L, plan.sourceToEditedTime(0L))
        assertEquals(1_999L, plan.sourceToEditedTime(1_999L))
        assertNull(plan.sourceToEditedTime(2_000L))
        assertEquals(2_000L, plan.sourceToEditedTime(5_000L))
        assertEquals(17_000L, plan.sourceToEditedTime(20_000L))

        val mapped = plan.mapCaptions(
            listOf(
                Caption(0L, 6_000L, "before and after"),
                Caption(10_000L, 12_000L, "later"),
            ),
        )
        assertEquals(
            listOf(
                Caption(0L, 2_000L, "before and after"),
                Caption(2_000L, 3_000L, "before and after"),
                Caption(7_000L, 9_000L, "later"),
            ),
            mapped,
        )
    }

    @Test
    fun togglingAndRestoringKeepTheOriginalDecisions() {
        val plan = EditPlan(
            durationSamples = 20_000L,
            cuts = listOf(Cut("pause", 2_000L, 5_000L, "silence")),
        )

        val toggled = plan.toggle("pause")
        assertFalse(toggled.cuts.single().enabled)
        assertEquals(listOf(SampleRange(0L, 20_000L)), toggled.keptRanges())
        assertEquals(plan, plan.toggle("unknown"))
        assertFalse(toggled.restoreAll().cuts.single().enabled)
        assertEquals(plan.cuts.single().copy(enabled = false), toggled.restoreAll().cuts.single())
    }

    @Test
    fun cancellationAndMissingMediaAreTerminal() {
        val cancelled = EditingEngine("cancelled")
        cancelled.submit(0L, Change.Cancelled)
        assertEquals(SessionPhase.CANCELLED, cancelled.snapshot().phase)
        assertThrows(IllegalArgumentException::class.java) {
            cancelled.submit(0L, Change.MediaMissing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cancelled.submit(0L, Change.CaptionsReplaced(emptyList()))
        }

        val missing = EditingEngine("missing")
        missing.submit(0L, sourceFinalized())
        missing.submit(100L, Change.MediaMissing)
        assertEquals(SessionPhase.MISSING_MEDIA, missing.snapshot().phase)
        assertThrows(IllegalArgumentException::class.java) {
            missing.submit(100L, Change.Cancelled)
        }
        assertThrows(IllegalArgumentException::class.java) {
            missing.submit(100L, Change.CutsRestored)
        }
    }

    @Test
    fun callerListsAndReturnedSnapshotsAreIsolated() {
        val sourceCaptions = mutableListOf(Caption(1_000L, 2_000L, "first"))
        val sourceCuts = mutableListOf(Cut("pause", 3_000L, 5_000L, "silence"))
        val inputPlan = EditPlan(20_000L, sourceCuts)
        val engine = EditingEngine("session")
        engine.submit(0L, sourceFinalized())
        engine.submit(0L, Change.CaptionsReplaced(sourceCaptions))
        engine.submit(0L, Change.EditsReplaced(inputPlan))

        sourceCaptions += Caption(6_000L, 7_000L, "mutated")
        sourceCuts += Cut("second", 8_000L, 9_000L, "mutated")

        val snapshot = engine.snapshot()
        assertEquals(listOf(Caption(1_000L, 2_000L, "first")), snapshot.captions)
        assertEquals(listOf(Cut("pause", 3_000L, 5_000L, "silence")), snapshot.edits?.cuts)
        assertNotSame(snapshot, engine.snapshot())

        @Suppress("UNCHECKED_CAST")
        val captured = snapshot.captions as MutableList<Caption>
        assertThrows(UnsupportedOperationException::class.java) {
            captured += Caption(8_000L, 9_000L, "cannot mutate")
        }
    }

    @Test
    fun timelineConversionsAndAnchorMappingsGuardOverflow() {
        val timeline = Timeline()
        assertEquals(16_000L, timeline.samplesFromMillis(1_000L))
        assertEquals(1_000L, timeline.msFromSamples(16_000L))

        val anchor = VideoAnchor(sampleIndex = 16_000L, videoTimeUs = 2_000_000L)
        assertEquals(2_500_000L, timeline.videoTimeUsFromSample(anchor, 24_000L))
        assertEquals(24_000L, timeline.sampleFromVideoTimeUs(anchor, 2_500_000L))

        assertThrows(ArithmeticException::class.java) {
            timeline.samplesFromMillis(Long.MAX_VALUE)
        }
        assertThrows(ArithmeticException::class.java) {
            timeline.msFromSamples(Long.MAX_VALUE)
        }
        assertThrows(ArithmeticException::class.java) {
            timeline.videoTimeUsFromSample(VideoAnchor(Long.MAX_VALUE, 0L), 0L)
        }
        assertThrows(ArithmeticException::class.java) {
            timeline.sampleFromVideoTimeUs(VideoAnchor(0L, Long.MAX_VALUE), 0L)
        }
    }

    @Test
    fun editPlanRejectsPlansThatRemoveTooMuchOrOverlap() {
        assertThrows(IllegalArgumentException::class.java) {
            EditPlan(10_000L, listOf(Cut("all", 0L, 7_000L, "too much")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditPlan(
                20_000L,
                listOf(
                    Cut("one", 1_000L, 5_000L, "first"),
                    Cut("two", 4_000L, 6_000L, "overlap"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditPlan(20_000L, listOf(Cut("one", 1_000L, 2_000L, "a"), Cut("one", 3_000L, 4_000L, "b")))
        }
    }

    private fun sourceFinalized(): Change.SourceFinalized = Change.SourceFinalized(
        sourceId = "video",
        durationSamples = 20_000L,
        anchor = VideoAnchor(sampleIndex = 0L, videoTimeUs = 0L),
    )
}
