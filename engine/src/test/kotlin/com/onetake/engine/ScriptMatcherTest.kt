package com.onetake.engine

import org.junit.Assert.*
import org.junit.Test

class ScriptMatcherTest {
    private val lines = listOf(
        "Today I am going to show you how One Take works.",
        "Bright sunlight creates beautiful shadows across the distant mountain valley.",
        "Press record then speak clearly into your phone microphone.",
        "Export finished videos directly into your personal photo library.",
        "Purple elephants dance gracefully beside enormous sparkling silver fountains.")
    private fun say(matcher: ScriptMatcher, text: String, id: String = text) = matcher.consume(ScriptTranscript(id, text, 16000))

    @Test fun exampleAndContractionsAreCovered() {
        val matcher = FuzzyScriptMatcher(lines[0])
        say(matcher, "Today I'm going to show you how the One Take app works.")
        assertEquals(ScriptChunkState.COVERED, matcher.progress.chunks[0].state)
        assertTrue(matcher.progress.complete)
    }
    @Test fun skipsThreeChunksButDoesNotSearchFurtherAhead() {
        val matcher = FuzzyScriptMatcher(lines.joinToString("\n"))
        say(matcher, lines[4])
        assertEquals(0, matcher.progress.currentIndex)
        say(matcher, lines[3])
        assertEquals(4, matcher.progress.currentIndex)
        assertTrue(matcher.progress.chunks.take(3).all { it.state == ScriptChunkState.SKIPPED })
    }
    @Test fun repetitionsEmitHintsWithoutMovingCursorAndDuplicateDeliveryIsIgnored() {
        val events = mutableListOf<Change>()
        val matcher = FuzzyScriptMatcher(lines.joinToString("\n"), RecordingSession { _, change, _ -> events += change })
        say(matcher, lines[0], "first")
        say(matcher, lines[0], "again")
        say(matcher, lines[0], "again")
        say(matcher, lines[0], "third")
        assertEquals(1, matcher.progress.currentIndex)
        assertEquals(ScriptChunkState.REPEATED, matcher.progress.chunks[0].state)
        assertEquals(listOf(2, 3), events.filterIsInstance<Change.TakeAttemptObserved>().map { it.attempt.attempt })
    }
    @Test fun offScriptSpeechStaysPending() {
        val matcher = FuzzyScriptMatcher(lines[0])
        say(matcher, "I am wondering whether we should eat pizza tonight because it might rain tomorrow")
        assertEquals(ScriptChunkState.PENDING, matcher.progress.chunks.single().state)
        assertEquals(0, matcher.progress.currentIndex)
    }
    @Test fun partialSegmentsAccumulateAndTypoAndSynonymAreAllowed() {
        val matcher = FuzzyScriptMatcher("Demonstrate bright yellow flowers beside beautiful mountains.", threshold = 0.9)
        say(matcher, "show bright yellow", "a")
        assertEquals(ScriptChunkState.MISMATCHED, matcher.progress.chunks.single().state)
        say(matcher, "flowers beside beautiful mountans", "b")
        assertTrue(matcher.progress.complete)
    }
    @Test fun multipleChunksInOneSegmentAndManualNavigation() {
        val events = mutableListOf<Pair<Change, ClockDomain>>()
        val matcher = FuzzyScriptMatcher(lines.joinToString("\n"), RecordingSession { _, change, clock -> events += change to clock })
        say(matcher, lines.take(2).joinToString(" "))
        assertEquals(2, matcher.progress.currentIndex)
        matcher.next(123)
        assertEquals(ScriptChunkState.SKIPPED, matcher.progress.chunks[2].state)
        matcher.previous(124)
        assertEquals(2, matcher.progress.currentIndex)
        val manual = events.last()
        assertEquals(ScriptProgressReason.MANUAL_PREVIOUS, (manual.first as Change.ScriptProgressObserved).progress.reason)
        assertEquals(ClockDomain.CAPTURE_ESTIMATE, manual.second)
    }
    @Test fun chunkIdsAreStableAndLongClausesAreBalanced() {
        val script = (1..31).joinToString(" ") { "word$it" }
        val chunks = ScriptChunker.split(script)
        assertEquals(chunks, ScriptChunker.split(script))
        assertTrue(chunks.all { it.text.split(' ').size in 8..15 })
        assertEquals(chunks, ScriptChunker.split("Introduction.\n$script").drop(1))
        val duplicates = ScriptChunker.split("Hello world.\nHello world.")
        assertEquals(2, duplicates.map { it.id }.distinct().size)
        assertTrue(ScriptChunker.split("  ").isEmpty())
        assertEquals(1, ScriptChunker.split("First, open the camera, then choose your recording quality.").size)
    }
    @Test fun sessionEventsReplayAndFinalizationPreservesProgress() {
        val history = mutableListOf<Event>()
        val engine = EditingEngine("script", EventSink { history += it })
        engine.submit(0, Change.CaptureRequested("take.mp4"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        val matcher = FuzzyScriptMatcher(lines[0], RecordingSession { at, change, clock -> engine.submit(at, change, clock) })
        say(matcher, lines[0], "first")
        say(matcher, lines[0], "second")
        engine.submit(16000, Change.SourceFinalized("source", 16000, VideoAnchor(0, 0)))
        assertEquals(matcher.progress, engine.snapshot().scriptProgress)
        assertEquals(engine.snapshot(), EditingEngine("script", history = history).snapshot())
        assertEquals(1, engine.snapshot().takeAttempts.size)
    }
    @Test fun twoHundredChunksUnderFiveMillisecondsPerSegment() {
        val script = (0 until 200).joinToString("\n") { index ->
            "Chapter $index explains topic${index} with example${index} and detail${index} for readers."
        }
        // Warm the JVM before checking latency; class loading/JIT is not segment processing.
        repeat(3) {
            val warmup = FuzzyScriptMatcher(script)
            warmup.progress.chunks.forEachIndexed { index, entry -> say(warmup, entry.chunk.text, "warm-$index") }
        }
        repeat(3) { pass ->
            val matcher = FuzzyScriptMatcher(script)
            val started = System.nanoTime()
            var maximum = 0L
            repeat(200) { index ->
                val segmentStart = System.nanoTime()
                say(matcher, "Chapter $index explains topic${index} with example${index} and detail${index} for readers.", "$pass-$index")
                maximum = maxOf(maximum, System.nanoTime() - segmentStart)
            }
            val average = (System.nanoTime() - started) / 200.0 / 1_000_000
            println("200-chunk matcher pass $pass: $average ms/segment, max ${maximum / 1_000_000.0} ms")
            assertTrue("Maximum ${maximum / 1_000_000.0} ms/segment", maximum < 5_000_000)
            assertTrue("$average ms/segment", average < 5.0)
            assertTrue(matcher.progress.complete)
        }
    }
}
