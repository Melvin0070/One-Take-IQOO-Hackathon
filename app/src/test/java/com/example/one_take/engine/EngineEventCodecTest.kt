package com.example.one_take.engine

import com.onetake.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class EngineEventCodecTest {
    @Test fun wordEvidenceRoundTripsAndLegacyCaptionsRemainReadable() {
        val event = Event(1, "words", 0, Change.CaptionsReplaced(listOf(
            Caption(0, 32_000, "um hello", listOf(TranscriptWord(3_200, 8_000, "um", 0.9f)))
        )))
        assertEquals(event, EngineEventCodec.decode(EngineEventCodec.encode(event)))
        val legacy = Event(1, "legacy", 0, Change.CaptionsReplaced(listOf(Caption(0, 16_000, "hello"))))
        assertEquals(legacy, EngineEventCodec.decode(EngineEventCodec.encode(legacy)))
    }

    @Test fun everyEventSurvivesEncodingAndReplay() {
        val changes = listOf(
            Change.SourceFinalized("content", 160_000, VideoAnchor(0, 0)),
            Change.CaptionsReplaced(listOf(Caption(0, 16_000, "Hello\nनमस्ते"))),
            Change.EditsReplaced(EditPlan(160_000, listOf(Cut("pause", 32_000, 48_000, "silence")))),
            Change.CutToggled("pause"), Change.CutsRestored, Change.MediaMissing,
        )
        val engine = EditingEngine("test")
        val history = changes.map { engine.submit(0, it) }
        val decoded = history.map { EngineEventCodec.decode(EngineEventCodec.encode(it)) }
        assertEquals(history, decoded)
        assertEquals(engine.snapshot(), EditingEngine("test", history = decoded).snapshot())
        val cancelled = Event(1, "other", 0, Change.Cancelled)
        assertEquals(cancelled, EngineEventCodec.decode(EngineEventCodec.encode(cancelled)))
    }

    @Test fun pauseCandidatePayloadSurvivesRoundTrip() {
        val payload = """{"version":1,"sequence":3,"session":"capture","sample":48000,"clock":"RECOGNIZER","change":{"type":"pause-candidate","id":"pause-16000-48000","start":16000,"end":48000}}"""
        val decoded = EngineEventCodec.decode(payload)
        val encoded = JSONObject(EngineEventCodec.encode(decoded))
        assertEquals("RECOGNIZER", encoded.getString("clock"))
        assertEquals("pause-candidate", encoded.getJSONObject("change").getString("type"))
        assertEquals(16000L, encoded.getJSONObject("change").getLong("start"))
        assertEquals(48000L, encoded.getJSONObject("change").getLong("end"))
        assertEquals(decoded, EngineEventCodec.decode(encoded.toString()))
    }

    @Test fun unknownVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EngineEventCodec.decode("{\"version\":2}") }
    }

    @Test fun allLiveCaptureChangesAndClockDomainsSurviveRoundTrip() {
        val events = listOf(
            Event(1, "capture", 0, Change.CaptureRequested("take.mp4"), ClockDomain.CAPTURE_ESTIMATE),
            Event(2, "capture", 0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE),
            Event(3, "capture", 16_000, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE),
            Event(4, "capture", 16_000, Change.CaptureFailed("encoder"), ClockDomain.CAPTURE_ESTIMATE),
            Event(5, "capture", 16_000, Change.CaptureInterrupted("process"), ClockDomain.CAPTURE_ESTIMATE),
            Event(6, "capture", 16_000, Change.ProvisionalTranscript("hello"), ClockDomain.RECOGNIZER),
            Event(
                7,
                "capture",
                16_000,
                Change.VisionObserved(VisionObservation(true, "NPU", 0.4f, 0.5f, offAxis = true)),
                ClockDomain.CAPTURE_ESTIMATE,
            ),
        )

        events.forEach { event ->
            assertEquals(event, EngineEventCodec.decode(EngineEventCodec.encode(event)))
        }
    }

    @Test fun missingClockInLegacyPayloadDefaultsToMedia() {
        val event = Event(1, "legacy", 0, Change.Cancelled, ClockDomain.MEDIA)
        val payload = JSONObject(EngineEventCodec.encode(event)).apply { remove("clock") }.toString()

        val decoded = EngineEventCodec.decode(payload)

        assertEquals(event, decoded)
        assertEquals(ClockDomain.MEDIA, decoded.clock)
    }
}
