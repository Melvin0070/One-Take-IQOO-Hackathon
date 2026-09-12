package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseEngineTest {
    @Test
    fun pauseCandidatesReplayPreserveOrderAndSurviveFinalization() {
        val events = mutableListOf<Event>()
        val engine = EditingEngine("pause-session", EventSink { events += it })
        val first = PauseCandidate("pause-0-23040", 0L, 23_040L)
        val second = PauseCandidate("pause-24000-48000", 24_000L, 48_000L)

        engine.submit(0L, Change.CaptureRequested("microphone"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(first.endSample, Change.PauseCandidateObserved(first), ClockDomain.RECOGNIZER)
        engine.submit(second.endSample, Change.PauseCandidateObserved(second), ClockDomain.RECOGNIZER)
        engine.submit(50_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(50_000L, finalized(60_000L), ClockDomain.MEDIA)

        assertEquals(listOf(first, second), engine.snapshot().pauseCandidates)
        assertNull(engine.snapshot().edits)
        assertEquals(
            listOf(ClockDomain.RECOGNIZER, ClockDomain.RECOGNIZER),
            events.filter { it.change is Change.PauseCandidateObserved }.map { it.clock },
        )

        val replayed = EditingEngine("pause-session", history = events)
        assertEquals(engine.snapshot(), replayed.snapshot())
        assertTrue(engine.snapshot().pauseCandidates !== replayed.snapshot().pauseCandidates)

        @Suppress("UNCHECKED_CAST")
        val snapshotCandidates = engine.snapshot().pauseCandidates as MutableList<PauseCandidate>
        assertThrows(UnsupportedOperationException::class.java) {
            snapshotCandidates += PauseCandidate("later", 50_000L, 51_000L)
        }
    }

    @Test
    fun duplicateAndOverlappingCandidatesAreRejectedWithoutChangingState() {
        val engine = EditingEngine("pause-validation")
        val first = PauseCandidate("first", 5_000L, 10_000L)
        engine.submit(0L, Change.CaptureRequested("microphone"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(first.endSample, Change.PauseCandidateObserved(first), ClockDomain.RECOGNIZER)
        val before = engine.snapshot()

        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(12_000L, Change.PauseCandidateObserved(first), ClockDomain.RECOGNIZER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(
                13_000L,
                Change.PauseCandidateObserved(PauseCandidate("overlap", 9_000L, 13_000L)),
                ClockDomain.RECOGNIZER,
            )
        }
        assertEquals(before, engine.snapshot())
    }

    @Test
    fun pauseCandidatesRequireRecognizerClockAndActiveCapture() {
        val candidate = PauseCandidate("pause", 0L, 20_000L)
        val engine = EditingEngine("pause-clock")
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(20_000L, Change.PauseCandidateObserved(candidate))
        }

        engine.submit(0L, Change.CaptureRequested("microphone"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(20_000L, Change.PauseCandidateObserved(candidate), ClockDomain.MEDIA)
        }
        engine.submit(20_000L, Change.PauseCandidateObserved(candidate), ClockDomain.RECOGNIZER)
        engine.submit(20_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(20_000L, finalized(30_000L), ClockDomain.MEDIA)

        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(20_000L, Change.PauseCandidateObserved(PauseCandidate("late", 20_000L, 25_000L)), ClockDomain.RECOGNIZER)
        }
    }

    @Test
    fun detectorEmitsOnlyForAChunkInvariantInteriorPause() {
        val stream = concat(
            pcm(StreamingPauseDetector.FRAME_SAMPLES * 50, 0.1f),
            pcm(StreamingPauseDetector.FRAME_SAMPLES * 100, 0f),
            pcm(StreamingPauseDetector.FRAME_SAMPLES * 50, 0.1f),
        )
        val expected = listOf(PauseCandidate("pause-19200-44800", 19_200L, 44_800L))

        val wholeChunk = StreamingPauseDetector().append(stream)
        val splitChunks = appendInChunks(stream, intArrayOf(1, 17, 319, 7, 1_003, 64, 2_509))

        assertEquals(expected, wholeChunk)
        assertEquals(expected, splitChunks)
    }

    @Test
    fun detectorRejectsLeadingTrailingShortAndUnanchoredSilence() {
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val leading = concat(pcm(frame * 61, 0f), pcm(frame, 0.1f))
        val trailing = concat(pcm(frame, 0.1f), pcm(frame * 61, 0f))
        val short = concat(pcm(frame, 0.1f), pcm(frame * 60, 0f), pcm(frame, 0.1f))
        val allQuiet = pcm(frame * 100, 0f)

        assertTrue(StreamingPauseDetector().append(leading).isEmpty())
        assertTrue(StreamingPauseDetector().append(trailing).isEmpty())
        assertTrue(StreamingPauseDetector().append(short).isEmpty())
        assertTrue(StreamingPauseDetector().append(allQuiet).isEmpty())
    }

    @Test
    fun detectorResetsOnNoiseAndInvalidPcm() {
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val noise = concat(
            pcm(frame, 0.1f),
            pcm(frame * 61, 0.001f),
            pcm(frame, 0.1f),
        )
        val invalid = concat(
            pcm(frame, 0.1f),
            pcm(frame * 61, 0f),
            floatArrayOf(Float.NaN),
            pcm(frame * 2, 0.1f),
        )

        assertTrue(StreamingPauseDetector().append(noise).isEmpty())
        assertTrue(StreamingPauseDetector().append(invalid).isEmpty())
    }

    @Test
    fun detectorKeepsStrongLeftAnchorAcrossWeakSpeechTail() {
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, 0.1f),
            pcm(frame * 3, 0.002f),
            pcm(frame * 100, 0f),
            pcm(frame * 50, 0.1f),
        )

        assertEquals(
            listOf(PauseCandidate("pause-20160-45760", 20_160L, 45_760L)),
            StreamingPauseDetector().append(stream),
        )
    }

    private fun appendInChunks(samples: FloatArray, sizes: IntArray): List<PauseCandidate> {
        val detector = StreamingPauseDetector()
        val candidates = ArrayList<PauseCandidate>()
        var offset = 0
        var sizeIndex = 0
        while (offset < samples.size) {
            val requested = sizes[sizeIndex % sizes.size]
            val end = minOf(samples.size, offset + requested)
            candidates += detector.append(samples.copyOfRange(offset, end))
            offset = end
            sizeIndex++
        }
        return candidates
    }

    private fun pcm(count: Int, value: Float): FloatArray = FloatArray(count) { value }

    private fun concat(vararg chunks: FloatArray): FloatArray {
        val total = chunks.sumOf { it.size }
        val result = FloatArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun finalized(durationSamples: Long): Change.SourceFinalized = Change.SourceFinalized(
        sourceId = "video",
        durationSamples = durationSamples,
        anchor = VideoAnchor(sampleIndex = 0L, videoTimeUs = 0L),
    )
}
