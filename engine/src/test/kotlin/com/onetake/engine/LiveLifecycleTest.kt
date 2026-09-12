package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLifecycleTest {
    @Test
    fun fullCaptureReplayPreservesDomainsAndFinalCaptionState() {
        val events = mutableListOf<Event>()
        val original = EditingEngine("capture", EventSink { events += it })
        val vision = VisionObservation(
            faceInFrame = true,
            processor = "CPU",
            centerX = 0.5f,
            centerY = 0.42f,
            offAxis = false,
        )

        original.submit(0L, Change.CaptureRequested("front-camera"), ClockDomain.CAPTURE_ESTIMATE)
        original.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        original.submit(320L, Change.ProvisionalTranscript("hello"), ClockDomain.RECOGNIZER)
        original.submit(320L, Change.VisionObserved(vision), ClockDomain.CAPTURE_ESTIMATE)
        original.submit(16_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)
        original.submit(16_000L, finalized(), ClockDomain.MEDIA)
        val captions = listOf(Caption(1_000L, 2_000L, "hello"))
        original.submit(2_000L, Change.CaptionsReplaced(captions), ClockDomain.MEDIA)

        val replayed = EditingEngine("capture", history = events)
        val state = replayed.snapshot()
        assertEquals(original.snapshot(), state)
        assertEquals(SessionPhase.READY, state.phase)
        assertTrue(state.captureRequested)
        assertTrue(state.captureStarted)
        assertEquals("front-camera", state.captureSourceName)
        assertNull(state.provisionalText)
        assertEquals(vision, state.lastVision)
        assertEquals(captions, state.captions)
        assertEquals(
            listOf(
                ClockDomain.CAPTURE_ESTIMATE,
                ClockDomain.CAPTURE_ESTIMATE,
                ClockDomain.RECOGNIZER,
                ClockDomain.CAPTURE_ESTIMATE,
                ClockDomain.CAPTURE_ESTIMATE,
                ClockDomain.MEDIA,
                ClockDomain.MEDIA,
            ),
            events.map { it.clock },
        )
    }

    @Test
    fun rapidStopAllowsLateCameraStartBeforeFinalization() {
        val engine = EditingEngine("rapid")
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(4L, Change.StopRequested("tap"), ClockDomain.CAPTURE_ESTIMATE)
        assertEquals(SessionPhase.FINALIZING, engine.snapshot().phase)

        engine.submit(4L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        assertTrue(engine.snapshot().captureStarted)
        engine.submit(4L, finalized(), ClockDomain.MEDIA)
        assertEquals(SessionPhase.READY, engine.snapshot().phase)
    }

    @Test
    fun interruptedCaptureCanBeRecoveredByValidFinalizedMedia() {
        val engine = EditingEngine("interrupted")
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(100L, Change.ProvisionalTranscript("in progress"), ClockDomain.RECOGNIZER)
        val vision = VisionObservation(true, "GPU", 0.3f, 0.6f, offAxis = true)
        engine.submit(100L, Change.VisionObserved(vision), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(100L, Change.StopRequested("screen off"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(
            100L,
            Change.CaptureInterrupted("process was reclaimed"),
            ClockDomain.CAPTURE_ESTIMATE,
        )
        assertEquals(SessionPhase.INTERRUPTED, engine.snapshot().phase)

        engine.submit(8_000L, finalized(), ClockDomain.MEDIA)
        val state = engine.snapshot()
        assertEquals(SessionPhase.READY, state.phase)
        assertTrue(state.captureRequested)
        assertTrue(state.captureStarted)
        assertEquals(vision, state.lastVision)
        assertNull(state.provisionalText)
    }

    @Test
    fun failedCaptureIsTerminalAndRetainsFailureReason() {
        val engine = EditingEngine("failed")
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(
            200L,
            Change.CaptureFailed("encoder unavailable"),
            ClockDomain.CAPTURE_ESTIMATE,
        )
        assertEquals(SessionPhase.FAILED, engine.snapshot().phase)
        assertEquals("encoder unavailable", engine.snapshot().failureReason)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(200L, Change.ProvisionalTranscript("late"), ClockDomain.RECOGNIZER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(200L, finalized(), ClockDomain.MEDIA)
        }
    }

    @Test
    fun cancellationIsAllowedWhileFinalizingAndAfterLegacyFinalization() {
        val finalizing = EditingEngine("cancel-finalizing")
        finalizing.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        finalizing.submit(0L, Change.StopRequested("tap"), ClockDomain.CAPTURE_ESTIMATE)
        finalizing.submit(0L, Change.Cancelled)
        assertEquals(SessionPhase.CANCELLED, finalizing.snapshot().phase)

        val ready = EditingEngine("cancel-ready")
        ready.submit(0L, finalized())
        ready.submit(0L, Change.Cancelled)
        assertEquals(SessionPhase.CANCELLED, ready.snapshot().phase)
        assertEquals("video", ready.snapshot().sourceId)
    }

    @Test
    fun duplicateStartAndOutOfOrderCaptureRequestsAreRejected() {
        val engine = EditingEngine("duplicates")
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(1L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        }

        val legacy = EditingEngine("legacy")
        legacy.submit(0L, finalized())
        assertThrows(IllegalArgumentException::class.java) {
            legacy.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        }
    }

    @Test
    fun lateObservationsCannotOverwriteFinalizedState() {
        val engine = EditingEngine("late")
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(100L, Change.StopRequested("tap"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(100L, finalized(), ClockDomain.MEDIA)
        val captions = listOf(Caption(0L, 1_000L, "final"))
        engine.submit(1_000L, Change.CaptionsReplaced(captions))

        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(100L, Change.ProvisionalTranscript("stale"), ClockDomain.RECOGNIZER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(
                100L,
                Change.VisionObserved(VisionObservation(true, "UNKNOWN")),
                ClockDomain.CAPTURE_ESTIMATE,
            )
        }
        assertEquals(captions, engine.snapshot().captions)
        assertNull(engine.snapshot().provisionalText)
        assertFalse(engine.snapshot().phase == SessionPhase.FINALIZING)
    }

    @Test
    fun eachChangeRequiresItsDeclaredClockDomain() {
        val engine = EditingEngine("clocks")
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(0L, Change.CaptureRequested("camera"))
        }
        engine.submit(0L, Change.CaptureRequested("camera"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)

        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(0L, Change.ProvisionalTranscript("text"), ClockDomain.CAPTURE_ESTIMATE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(
                0L,
                Change.VisionObserved(VisionObservation(true, "CPU")),
                ClockDomain.RECOGNIZER,
            )
        }
        engine.submit(0L, Change.StopRequested("tap"), ClockDomain.CAPTURE_ESTIMATE)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(0L, finalized(), ClockDomain.CAPTURE_ESTIMATE)
        }
    }

    @Test
    fun mediaSampleBoundsApplyOnlyToMediaClockAfterFinalization() {
        val engine = EditingEngine("bounds")
        engine.submit(0L, finalized())
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(20_001L, Change.CaptionsReplaced(emptyList()), ClockDomain.MEDIA)
        }
    }

    @Test
    fun visionObservationRequiresNormalizedFiniteCentersAndKnownProcessor() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionObservation(true, "CPU", centerX = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionObservation(true, "CPU", centerY = Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionObservation(true, "CPU", centerX = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionObservation(true, "DSP")
        }
        assertEquals(VisionObservation(false, "UNKNOWN"), VisionObservation(false, "UNKNOWN"))
    }

    private fun finalized(): Change.SourceFinalized = Change.SourceFinalized(
        sourceId = "video",
        durationSamples = 20_000L,
        anchor = VideoAnchor(sampleIndex = 0L, videoTimeUs = 0L),
    )
}
