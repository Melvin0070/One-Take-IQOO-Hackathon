package com.example.one_take.engine

import com.onetake.engine.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingSessionJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    private val live = listOf(
        TranscriptSegment("seg", 0, 16_000, "Welcome to One Take", listOf(TranscriptWord(0, 8_000, "Welcome", .75f))),
        Silence("sil", 16_000, 40_000, VoiceActivitySource.SILERO),
        Filler("fil", 42_000, 44_000, "you know"),
        GazeSample("gaze-a", 30_000, faceInFrame = true, onCamera = false),
        GazeSample("gaze-b", 31_000, faceInFrame = false),
        TakeAttempt("take", "group", 2, 0, 16_000),
    )

    @Test fun everySignalAndTheHeaderRoundTripThroughTheJournal() {
        val directory = temporary.newFolder()
        val store = LiveCaptureStore(directory)
        val id = store.begin("take.mp4", SessionMode.SCRIPT, "Welcome to One Take.", 1_726_000_000_000)
        store.append(id, 0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        live.forEach { store.append(id, it.endSample, Change.SignalObserved(it), it.liveClock) }
        store.append(id, 48_000, Change.SourceFinalized("fingerprint", 48_000, VideoAnchor(0, 0)), ClockDomain.MEDIA)
        live.forEach { store.append(id, it.endSample, Change.SignalObserved(it), ClockDomain.MEDIA) }

        // A fresh store instance reads only what the journal holds.
        val session = LiveCaptureStore(directory).session(id)
        assertEquals(SessionHeader("take.mp4", SessionMode.SCRIPT, "Welcome to One Take.", 1_726_000_000_000), session.header)
        assertEquals(live, session.signals.filter { it.clock != ClockDomain.MEDIA }.map { it.signal })
        assertEquals(live, session.signals<SessionSignal>(ClockDomain.MEDIA))
        assertEquals(live.map { it.liveClock }, session.signals.take(live.size).map { it.clock })
        assertEquals(SessionPhase.READY, session.phase)
    }

    @Test fun eachSignalPayloadSurvivesTheCodecInBothClocks() {
        live.forEach { signal ->
            listOf(signal.liveClock, ClockDomain.MEDIA).forEach { clock ->
                val event = Event(3, "capture", signal.endSample, Change.SignalObserved(signal), clock)
                assertEquals(event, EngineEventCodec.decode(EngineEventCodec.encode(event)))
            }
        }
    }

    @Test fun legacyHeadersAndMatcherTakeAttemptsRemainReadable() {
        val header = JSONObject(EngineEventCodec.encode(Event(1, "capture", 0, Change.CaptureRequested("take.mp4"), ClockDomain.CAPTURE_ESTIMATE)))
        header.getJSONObject("change").remove("mode")
        assertEquals(Change.CaptureRequested("take.mp4", SessionMode.ASSISTED, null, null), EngineEventCodec.decode(header.toString()).change)

        val legacy = """{"version":1,"sequence":4,"session":"capture","sample":48000,"clock":"RECOGNIZER","change":{"type":"take-attempt","chunk":"abc-0","attempt":2,"segment":"repeat"}}"""
        assertEquals(Change.SignalObserved(TakeAttempt("abc-0#2", "abc-0", 2, 48_000, 48_000)), EngineEventCodec.decode(legacy).change)
    }

    @Test fun ledgerWrittenBeforeSignalsReplaysThroughTheStore() {
        val directory = temporary.newFolder()
        val id = "00000000-0000-4000-8000-000000000045"
        val journal = FileEventJournal(java.io.File(directory, "$id.ledger"))
        listOf(
            """{"version":1,"sequence":1,"session":"$id","sample":0,"clock":"CAPTURE_ESTIMATE","change":{"type":"capture-requested","name":"old.mp4"}}""",
            """{"version":1,"sequence":2,"session":"$id","sample":0,"clock":"CAPTURE_ESTIMATE","change":{"type":"capture-started"}}""",
            """{"version":1,"sequence":3,"session":"$id","sample":32000,"clock":"RECOGNIZER","change":{"type":"take-attempt","chunk":"abc-0","attempt":2,"segment":"repeat"}}""",
            """{"version":1,"sequence":4,"session":"$id","sample":48000,"clock":"RECOGNIZER","change":{"type":"pause-candidate","id":"p","start":16000,"end":48000}}""",
            """{"version":1,"sequence":5,"session":"$id","sample":64000,"clock":"MEDIA","change":{"type":"source-finalized","source":"fp","duration":64000,"anchorSample":0,"anchorVideoUs":0}}""",
        ).forEachIndexed { index, payload -> journal.append(JournalRecord(index + 1L, payload)) }

        val store = LiveCaptureStore(directory)
        assertEquals(SessionPhase.READY, store.snapshot(id).phase)
        val session = store.session(id)
        assertEquals(SessionHeader("old.mp4", SessionMode.ASSISTED, null, null), session.header)
        assertEquals(listOf(TakeAttempt("abc-0#2", "abc-0", 2, 32_000, 32_000)), session.signals<TakeAttempt>(ClockDomain.RECOGNIZER))
        store.append(id, 40_000, Change.SignalObserved(live[1]), ClockDomain.MEDIA)
        assertEquals(listOf(live[1]), store.session(id).signals<Silence>(ClockDomain.MEDIA))
    }

    @Test fun coordinatorStyleDedupeUsesSignalKeys() {
        val store = LiveCaptureStore(temporary.newFolder())
        val id = store.begin("take.mp4")
        store.append(id, 0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        store.append(id, 16_000, Change.SignalObserved(live[0]), ClockDomain.RECOGNIZER)
        assertTrue(live[0].key(ClockDomain.RECOGNIZER) in store.snapshot(id).signalKeys)
        assertFalse(live[0].key(ClockDomain.MEDIA) in store.snapshot(id).signalKeys)
    }
}
