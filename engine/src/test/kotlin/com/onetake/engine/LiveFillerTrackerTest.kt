package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFillerTrackerTest {
    @Test
    fun recordsTimedFillersIntoTheSessionOnTheRecognizerClock() {
        val events = mutableListOf<Event>()
        val tracker = LiveFillerTracker(startedSession(events))

        tracker.onSegment(segment("seg-1", transcript.take(6)))

        val read = RecordingSessionReader.read(events)
        val fillers = read.signals<Filler>(ClockDomain.RECOGNIZER)
        assertEquals(listOf("so", "like"), fillers.map { it.text })
        assertEquals(Filler("filler-0-4000", 0, 4_000, "so"), fillers.first())
        assertEquals(fillers.map { it.endSample }, read.signals.map { it.sample })
        assertEquals(2, tracker.count)
    }

    @Test
    fun segmentsWithoutWordTimingsRecordNothing() {
        val recorded = mutableListOf<Change>()
        val tracker = LiveFillerTracker { _, change, _ -> recorded += change }

        tracker.onSegment(TranscriptSegment("seg-1", 0, 32_000, "um so like uh"))

        assertTrue(recorded.isEmpty())
        assertEquals(0, tracker.count)
    }

    @Test
    fun redeliveredSegmentIsNotRecordedOrCountedTwice() {
        val events = mutableListOf<Event>()
        val tracker = LiveFillerTracker(startedSession(events))
        val first = segment("seg-1", transcript.take(9))

        tracker.onSegment(first)
        tracker.onSegment(first)

        assertEquals(3, tracker.count)
        assertEquals(3, RecordingSessionReader.read(events).signals<Filler>(ClockDomain.RECOGNIZER).size)
    }

    @Test
    fun liveAndPostHocFindTheSameFillersForEverySegmentation() {
        val expected = FillerLexicon.find(transcript)
        assertEquals(listOf("so", "like", "um", "basically", "uh", "you know", "hmm"), expected.map { it.text })

        for (first in 1 until transcript.size) {
            for (second in first until transcript.size) {
                val parts = listOf(0, first, second, transcript.size).zipWithNext()
                    .filter { (from, to) -> to > from }
                    .map { (from, to) -> transcript.subList(from, to) }

                val recorded = mutableListOf<Filler>()
                val tracker = LiveFillerTracker { sample, change, clock ->
                    val filler = (change as Change.SignalObserved).signal as Filler
                    assertEquals(ClockDomain.RECOGNIZER, clock)
                    assertEquals(filler.endSample, sample)
                    recorded += filler
                }
                parts.forEachIndexed { index, words -> tracker.onSegment(segment("seg-$index", words)) }

                val postHoc = SpeechSuggestionDetector().detect(parts.map(::caption))
                    .filter { it.kind == SpeechSuggestion.Kind.FILLER }
                val split = "split at $first/$second"
                assertEquals(split, expected.map { it.toFiller() }, recorded)
                assertEquals(split, expected.size, tracker.count)
                assertEquals(split, expected.map { it.startSample to it.endSample }, postHoc.map { it.startSample to it.endSample })
            }
        }
    }

    private fun FillerMatch.toFiller() = Filler(LiveFillerTracker.fillerId(this), startSample, endSample, text)

    private fun startedSession(events: MutableList<Event>): RecordingSession {
        val engine = EditingEngine("session", { events += it })
        engine.submit(0, Change.CaptureRequested("take.mp4", SessionMode.ASSISTED), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        return RecordingSession { sample, change, clock -> engine.submit(sample, change, clock) }
    }

    private fun segment(id: String, words: List<TranscriptWord>) = TranscriptSegment(
        id,
        words.first().startSample,
        words.last().endSample,
        words.joinToString(" ") { it.text },
        words,
    )

    private fun caption(words: List<TranscriptWord>) =
        Caption(words.first().startSample, words.last().endSample, words.joinToString(" ") { it.text }, words)

    private companion object {
        const val WORD = 4_000L
        const val TIGHT = 800L
        const val PAUSE = 8_000L

        /** Every lexicon kind, markers in and out of filler use, and a trailing undecidable "so". */
        val transcript: List<TranscriptWord> = run {
            var cursor = 0L
            listOf(
                "So" to 0L, "today" to PAUSE, "we" to TIGHT, "ship" to TIGHT, "like" to PAUSE, "a" to PAUSE,
                "new" to TIGHT, "and" to TIGHT, "um," to TIGHT, "basically" to TIGHT, "uhh" to TIGHT,
                "it" to TIGHT, "works" to TIGHT, "you" to PAUSE, "know" to TIGHT, "I" to PAUSE, "like" to TIGHT,
                "that" to TIGHT, "so" to TIGHT, "much" to TIGHT, "Hmm." to PAUSE, "done" to PAUSE, "so" to PAUSE,
            ).map { (text, gapBefore) ->
                val start = cursor + gapBefore
                cursor = start + WORD
                TranscriptWord(start, cursor, text, .95f)
            }
        }
    }
}
