package com.example.one_take.engine

import com.onetake.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun matcherAndManualEventsSurviveExistingJournalAndSourceFinalization() {
        val store = LiveCaptureStore(temporary.newFolder())
        val id = store.begin("script.mp4")
        store.append(id, 0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        val matcher = FuzzyScriptMatcher(
            "Beautiful mountains surround the sparkling lake beside our quiet village.\n" +
                "Press record then speak clearly into your phone microphone.\n" +
                "Export finished videos directly into your personal photo library.",
            RecordingSession { sample, change, clock -> store.append(id, sample, change, clock) })
        matcher.consume(ScriptTranscript("a", "Beautiful mountains surround", 16000))
        assertEquals(ScriptChunkState.MISMATCHED, matcher.progress.chunks[0].state)
        matcher.consume(ScriptTranscript("b", "the sparkling lake beside our quiet village", 32000))
        matcher.consume(ScriptTranscript("repeat", "Beautiful mountains surround the sparkling lake beside our quiet village", 48000))
        matcher.next(48000)
        matcher.previous(48000)
        store.append(id, 48000, Change.SourceFinalized("fingerprint", 48000, VideoAnchor(0, 0)), ClockDomain.MEDIA)
        val history = store.events(id)
        assertEquals(matcher.progress, store.snapshot(id).scriptProgress)
        assertEquals(1, store.snapshot(id).takeAttempts.size)
        assertEquals(store.snapshot(id), EditingEngine(id, history = history).snapshot())
        assertTrue(history.filter { it.change is Change.TakeAttemptObserved }.all { it.clock == ClockDomain.RECOGNIZER })
        assertTrue(history.filter { (it.change as? Change.ScriptProgressObserved)?.progress?.reason == ScriptProgressReason.MANUAL_NEXT }
            .all { it.clock == ClockDomain.CAPTURE_ESTIMATE })
    }

    @Test fun allCoverageStatesRoundTripAndSnapshotsCannotBeMutated() {
        val entries = ScriptChunkState.entries.mapIndexed { index, state ->
            ChunkCoverage(ScriptChunk("chunk-$index", "Script $index"), state, .5, index)
        }.toMutableList()
        val event = Event(1, "test", 123, Change.ScriptProgressObserved(
            ScriptProgress(entries, 2, ScriptProgressReason.TRANSCRIPT)), ClockDomain.RECOGNIZER)
        assertEquals(event, EngineEventCodec.decode(EngineEventCodec.encode(event)))
        val engine = EditingEngine("test")
        engine.submit(0, Change.CaptureRequested("file.mp4"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(123, event.change, event.clock)
        entries.clear()
        assertEquals(5, engine.snapshot().scriptProgress!!.chunks.size)
    }
}
