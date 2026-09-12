package com.onetake.engine

import org.junit.Assert.*
import org.junit.Test

class RecordingSessionTest {
    private val script = "Welcome to One Take."
    private val segment = TranscriptSegment("seg-1", 0, 16_000, "Welcome to One Take",
        listOf(TranscriptWord(0, 4_000, "Welcome", .9f)))
    private val signals = listOf(
        segment,
        Silence("sil-1", 16_000, 48_000, VoiceActivitySource.SILERO),
        Filler("fil-1", 50_000, 52_000, "um"),
        GazeSample("gaze-1", 40_000, faceInFrame = true, onCamera = null),
        TakeAttempt("take-1", "group-1", 1, 0, 16_000),
    )

    private fun started(mode: SessionMode = SessionMode.SCRIPT) = EditingEngine("session").apply {
        submit(0, Change.CaptureRequested("take.mp4", mode, script.takeIf { mode == SessionMode.SCRIPT }, 1_000), ClockDomain.CAPTURE_ESTIMATE)
        submit(0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
    }

    @Test fun readerReconstructsHeaderAndEverySignalInOrder() {
        val events = mutableListOf<Event>()
        val engine = EditingEngine("session", { events += it })
        engine.submit(0, Change.CaptureRequested("take.mp4", SessionMode.SCRIPT, script, 1_000), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        val session = RecordingSession { sample, change, clock -> engine.submit(sample, change, clock) }
        signals.forEach(session::signal)
        engine.submit(64_000, Change.ProvisionalTranscript("ignored by the reader"), ClockDomain.RECOGNIZER)
        engine.submit(64_000, Change.SourceFinalized("fingerprint", 64_000, VideoAnchor(0, 0)))
        engine.submit(48_000, Change.SignalObserved(Silence("sil-1", 17_000, 47_000, VoiceActivitySource.SILERO)))

        val read = RecordingSessionReader.read(events)
        assertEquals(SessionHeader("take.mp4", SessionMode.SCRIPT, script, 1_000), read.header)
        assertEquals(SessionPhase.READY, read.phase)
        assertEquals(64_000L, read.durationSamples)
        assertEquals(signals, read.signals.filter { it.clock != ClockDomain.MEDIA }.map { it.signal })
        assertEquals(listOf(ClockDomain.RECOGNIZER, ClockDomain.RECOGNIZER, ClockDomain.RECOGNIZER,
            ClockDomain.CAPTURE_ESTIMATE, ClockDomain.RECOGNIZER, ClockDomain.MEDIA), read.signals.map { it.clock })
        assertEquals(listOf(17_000L), read.signals<Silence>(ClockDomain.MEDIA).map { it.startSample })
        assertEquals(1, read.signals<Filler>().size)
    }

    @Test fun liveSignalsRequireAnActiveCaptureAndTheirOwnClock() {
        val requested = EditingEngine("session")
        requested.submit(0, Change.CaptureRequested("take.mp4"), ClockDomain.CAPTURE_ESTIMATE)
        assertThrows(IllegalArgumentException::class.java) { requested.submit(0, Change.SignalObserved(segment), ClockDomain.RECOGNIZER) }
        val engine = started()
        assertThrows(IllegalArgumentException::class.java) { engine.submit(0, Change.SignalObserved(segment), ClockDomain.CAPTURE_ESTIMATE) }
        assertThrows(IllegalArgumentException::class.java) { engine.submit(0, Change.SignalObserved(segment), ClockDomain.MEDIA) }
        engine.submit(16_000, Change.SignalObserved(segment), ClockDomain.RECOGNIZER)
        assertThrows(IllegalArgumentException::class.java) { engine.submit(16_000, Change.SignalObserved(segment), ClockDomain.RECOGNIZER) }
    }

    @Test fun mediaSignalsNeedFinalizedMediaAndStayWithinItsDuration() {
        val engine = started()
        engine.submit(16_000, Change.SignalObserved(segment), ClockDomain.RECOGNIZER)
        engine.submit(32_000, Change.SourceFinalized("fingerprint", 32_000, VideoAnchor(0, 0)))
        assertThrows(IllegalArgumentException::class.java) { engine.submit(16_000, Change.SignalObserved(segment), ClockDomain.RECOGNIZER) }
        engine.submit(16_000, Change.SignalObserved(segment), ClockDomain.MEDIA)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(32_000, Change.SignalObserved(Filler("late", 30_000, 40_000, "uh")), ClockDomain.MEDIA)
        }
        assertEquals(2, engine.snapshot().signalKeys.size)
    }

    @Test fun headerKeepsScriptsOutOfAssistedSessions() {
        assertThrows(IllegalArgumentException::class.java) {
            EditingEngine("s").submit(0, Change.CaptureRequested("take.mp4", SessionMode.ASSISTED, "script"), ClockDomain.CAPTURE_ESTIMATE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditingEngine("s").submit(0, Change.CaptureRequested("take.mp4", SessionMode.SCRIPT, " "), ClockDomain.CAPTURE_ESTIMATE)
        }
        val read = RecordingSessionReader.read(listOf(started(SessionMode.ASSISTED).let {
            Event(1, "session", 0, Change.CaptureRequested("take.mp4", SessionMode.ASSISTED, null, 5), ClockDomain.CAPTURE_ESTIMATE)
        }))
        assertEquals(SessionHeader("take.mp4", SessionMode.ASSISTED, null, 5), read.header)
        assertTrue(read.signals.isEmpty())
    }

    @Test fun invalidSignalsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { Silence("s", 10, 10, VoiceActivitySource.WEBRTC) }
        assertThrows(IllegalArgumentException::class.java) { Filler("f", 10, 5, "um") }
        assertThrows(IllegalArgumentException::class.java) { TakeAttempt("t", "g", 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { GazeSample("g", 0, faceInFrame = false, onCamera = true) }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptSegment("t", 100, 200, "hi", listOf(TranscriptWord(0, 150, "hi", 1f)))
        }
    }
}
