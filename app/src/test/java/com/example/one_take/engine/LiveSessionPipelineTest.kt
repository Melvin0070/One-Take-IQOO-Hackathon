package com.example.one_take.engine

import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionWord
import com.example.one_take.vision.FaceObservation
import com.onetake.engine.*
import org.junit.Assert.*
import org.junit.Test

class LiveSessionPipelineTest {
    private val log = mutableListOf<String>()
    private val signals = mutableListOf<Pair<String, SessionSignal>>()
    private val scriptChanges = mutableListOf<Change>()
    private val sink = object : LiveSessionSink {
        override fun signal(session: String, signal: SessionSignal, clock: ClockDomain) {
            assertEquals(signal.liveClock, clock)
            signals += session to signal
            log += "journal:${signal::class.simpleName}:${signal.id}"
        }
        override fun scriptEvent(session: String, sample: Long, change: Change, clock: ClockDomain) {
            scriptChanges += change
            log += "journal:${change::class.simpleName}"
        }
    }
    private val progressSeen = mutableListOf<ScriptProgress>()

    private fun pipeline(mode: SessionMode, script: String? = null) = LiveSessionPipeline(mode, script, sink,
        fillers = { TranscriptListener { log += "fillers:${it.id}" } },
        takes = { _, _ -> object : LiveTakeListener {
            override fun onSegment(segment: TranscriptSegment) { log += "takes:${segment.id}" }
            override fun onScriptProgress(segment: TranscriptSegment, progress: ScriptProgress) {
                progressSeen += progress
                log += "progress:${segment.id}"
            }
        } })

    private val first = CaptionSegment(0, 1_000, "Welcome to One Take", listOf(CaptionWord(0, 400, "Welcome", .9f)))
    private val second = CaptionSegment(1_000, 2_500, "today we record")
    private val third = CaptionSegment(2_500, 3_000, "the demo")

    @Test fun growingCommittedSnapshotsReachSessionAndDetectorsExactlyOnceInOrder() {
        val live = pipeline(SessionMode.ASSISTED)
        live.attach("take")
        live.onCommittedSegments(listOf(first))
        live.onCommittedSegments(listOf(first, second))
        live.onCommittedSegments(listOf(first, second))
        live.onCommittedSegments(listOf(first, second, third))

        assertEquals(listOf(
            "journal:TranscriptSegment:0-16000", "takes:0-16000", "fillers:0-16000",
            "journal:TranscriptSegment:16000-40000", "takes:16000-40000", "fillers:16000-40000",
            "journal:TranscriptSegment:40000-48000", "takes:40000-48000", "fillers:40000-48000",
        ), log)
        assertTrue(signals.all { it.first == "take" })
        val segment = signals.first().second as TranscriptSegment
        assertEquals("Welcome to One Take", segment.text)
        assertEquals(listOf(TranscriptWord(0, 6_400, "Welcome", .9f)), segment.words)
        assertTrue(scriptChanges.isEmpty())
    }

    @Test fun assistedModeCarriesNoScriptAndNeverStartsTheMatcher() {
        val live = pipeline(SessionMode.ASSISTED, "Welcome to One Take")
        assertNull(live.script)
        assertNull(live.scriptController)
        live.attach("take")
        live.onCommittedSegments(listOf(first))
        assertTrue(scriptChanges.isEmpty())
        assertTrue(progressSeen.isEmpty())
    }

    @Test fun scriptModeMatchesEachSegmentAfterJournalingItAndReportsProgressToTakes() {
        val live = pipeline(SessionMode.SCRIPT, "  Welcome to One Take.\nToday we record the demo.  ")
        assertEquals("Welcome to One Take.\nToday we record the demo.", live.script)
        val controller = checkNotNull(live.scriptController)
        live.attach("take")
        assertEquals(ScriptProgressReason.INITIAL, (scriptChanges.single() as Change.ScriptProgressObserved).progress.reason)

        live.onCommittedSegments(listOf(first))
        assertEquals(listOf("journal:ScriptProgressObserved", "journal:TranscriptSegment:0-16000",
            "journal:ScriptProgressObserved", "takes:0-16000", "progress:0-16000", "fillers:0-16000"), log)
        assertEquals(1, controller.progress.currentIndex)
        assertEquals(controller.progress, progressSeen.single())
    }

    @Test fun nothingIsConsumedBeforeTheSessionExistsAndATakeKeepsItsFirstSession() {
        val live = pipeline(SessionMode.SCRIPT, "Welcome to One Take.")
        live.onCommittedSegments(listOf(first))
        assertTrue(log.isEmpty())
        assertEquals(0, live.scriptController!!.progress.currentIndex)

        live.attach("first-take")
        live.attach("second-take")
        live.onCommittedSegments(listOf(first))
        assertEquals("first-take", live.sessionId)
        assertEquals(listOf("first-take"), signals.map { it.first })
        assertEquals(1, live.scriptController!!.progress.currentIndex)
    }

    @Test fun malformedWordTimingsKeepTheSegmentTextWithoutWords() {
        val live = pipeline(SessionMode.ASSISTED)
        live.attach("take")
        live.onCommittedSegments(listOf(first.copy(words = listOf(CaptionWord(0, 1_500, "Welcome", .9f)))))
        assertEquals(emptyList<TranscriptWord>(), (signals.single().second as TranscriptSegment).words)
    }

    @Test fun facePresenceChangesAfterCaptureStartBecomeCoalescedGazeSamples() {
        val live = pipeline(SessionMode.ASSISTED)
        live.attach("take")
        fun face(at: Long) = FaceObservation(at, .5f, .4f, .3f, .4f, .6f, .3f, offAxis = true)

        live.onFace(face(1_000), recordingStartedAtMs = 0, nowMs = 1_000)
        live.onFace(face(900), recordingStartedAtMs = 1_000, nowMs = 1_000)
        live.onFace(face(1_500), recordingStartedAtMs = 1_000, nowMs = 1_500)
        live.onFace(face(1_600), recordingStartedAtMs = 1_000, nowMs = 1_600)
        live.onFace(null, recordingStartedAtMs = 1_000, nowMs = 2_000)
        live.onFace(null, recordingStartedAtMs = 1_000, nowMs = 2_100)

        assertEquals(listOf(
            GazeSample("gaze-1", 8_000, faceInFrame = true, onCamera = null),
            GazeSample("gaze-2", 16_000, faceInFrame = false, onCamera = null),
        ), signals.map { it.second })
    }
}
