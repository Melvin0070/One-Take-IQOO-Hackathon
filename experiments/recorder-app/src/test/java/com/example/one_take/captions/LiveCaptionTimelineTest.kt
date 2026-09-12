package com.example.one_take.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionTimelineTest {
    @Test
    fun modelWordTimingsShiftWithTheLiveWindow() {
        val timeline = LiveCaptionTimeline()
        val words = listOf(
            CaptionWord(100L, 220L, "hello", 0.91f),
            CaptionWord(230L, 460L, "world", 0.84f),
        )

        timeline.append(
            windowStartMs = 500L,
            segments = listOf(CaptionSegment(0L, 500L, "hello world", words)),
            commitBeforeMs = 1_000L,
        )

        assertEquals(
            listOf(
                CaptionWord(600L, 720L, "hello", 0.91f),
                CaptionWord(730L, 960L, "world", 0.84f),
            ),
            timeline.snapshot().single().words,
        )
    }

    @Test
    fun deduplicatingAnOverlappingCaptionDropsUncertainWordTimings() {
        val timeline = LiveCaptionTimeline()
        timeline.append(
            0L,
            listOf(
                CaptionSegment(
                    0L,
                    500L,
                    "hello world",
                    listOf(
                        CaptionWord(50L, 180L, "hello", 0.9f),
                        CaptionWord(200L, 430L, "world", 0.8f),
                    ),
                )
            ),
            500L,
        )

        timeline.append(
            300L,
            listOf(
                CaptionSegment(
                    0L,
                    600L,
                    "world again",
                    listOf(
                        CaptionWord(40L, 180L, "world", 0.9f),
                        CaptionWord(220L, 540L, "again", 0.8f),
                    ),
                )
            ),
            900L,
        )

        assertEquals("again", timeline.snapshot().last().text)
        assertEquals(emptyList<CaptionWord>(), timeline.snapshot().last().words)
    }

    @Test
    fun commitHorizonIsAbsoluteAndCrossingSegmentWaitsForLaterWindow() {
        val timeline = LiveCaptionTimeline()

        timeline.append(
            windowStartMs = 1_000L,
            segments = listOf(
                CaptionSegment(0L, 400L, "first"),
                CaptionSegment(400L, 1_000L, "second"),
            ),
            commitBeforeMs = 1_400L,
        )

        assertEquals(listOf(CaptionSegment(1_000L, 1_400L, "first")), timeline.snapshot())
        assertEquals(1_400L, timeline.committedThroughMs)

        timeline.append(
            windowStartMs = 1_300L,
            segments = listOf(CaptionSegment(0L, 700L, "second tail")),
            commitBeforeMs = 2_000L,
        )

        assertEquals(
            listOf(
                CaptionSegment(1_000L, 1_400L, "first"),
                CaptionSegment(1_400L, 2_000L, "second tail"),
            ),
            timeline.snapshot(),
        )
    }

    @Test
    fun overlappingPrefixIsTrimmedOnlyWhenItsTimeOverlapsCommittedText() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0L, listOf(CaptionSegment(0L, 1_000L, "hello world")), 1_000L)

        timeline.append(700L, listOf(CaptionSegment(0L, 800L, "world again")), 1_500L)
        timeline.append(1_500L, listOf(CaptionSegment(0L, 400L, "again")), 1_900L)

        assertEquals(
            listOf(
                CaptionSegment(0L, 1_000L, "hello world"),
                CaptionSegment(1_000L, 1_500L, "again"),
                CaptionSegment(1_500L, 1_900L, "again"),
            ),
            timeline.snapshot(),
        )
    }

    @Test
    fun committedTimelineRemainsPositiveAndNonOverlappingAcrossWindows() {
        val timeline = LiveCaptionTimeline()
        timeline.append(
            0L,
            listOf(
                CaptionSegment(0L, 500L, "one"),
                CaptionSegment(500L, 1_000L, "two"),
            ),
            600L,
        )
        timeline.append(400L, listOf(CaptionSegment(0L, 700L, "two three")), 1_100L)

        val result = timeline.snapshot()
        assertEquals(
            listOf(
                CaptionSegment(0L, 500L, "one"),
                CaptionSegment(500L, 1_100L, "two three"),
            ),
            result,
        )
        assert(result.zipWithNext().all { (left, right) -> left.endMs <= right.startMs })
        assert(result.all { it.endMs > it.startMs })
    }

    @Test
    fun removesRepeatedPhraseAcrossCommittedLinesWithAnExtraLeadingWord() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0, listOf(CaptionSegment(0, 1_000, "The application records a video"),
            CaptionSegment(1_000, 1_500, "and adds")), 1_500)
        timeline.append(800, listOf(CaptionSegment(0, 1_500, "and records a video and adds subtitles")), 2_300)
        assertEquals("subtitles", timeline.snapshot().last().text)
    }

    @Test
    fun substitutedWordsInOverlappingSpeechAreNotDiscarded() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0, listOf(CaptionSegment(0, 500, "I want tea")), 500)
        timeline.append(200, listOf(CaptionSegment(0, 700, "I want coffee today")), 900)
        assertEquals("I want coffee today", timeline.snapshot().last().text)
    }

    @Test
    fun newerHypothesisReplacesCoveredPendingText() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0L, listOf(CaptionSegment(500L, 900L, "draft")), 400L)

        timeline.append(400L, listOf(CaptionSegment(100L, 600L, "final")), 1_000L)

        assertEquals(listOf(CaptionSegment(500L, 1_000L, "final")), timeline.snapshot())
    }

    @Test
    fun shorterRevisedHypothesisDoesNotResurrectAnOldPendingTail() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0L, listOf(CaptionSegment(0L, 1_000L, "hello imaginary tail")), 500L)

        // The same complete audio suffix is decoded again, with a shorter hypothesis.
        timeline.append(0L, listOf(CaptionSegment(0L, 600L, "hello")), 1_000L)

        assertEquals(listOf(CaptionSegment(0L, 600L, "hello")), timeline.snapshot())
    }

    @Test
    fun emptyOverlappingWindowDropsUncommittedTextInItsSafePrefix() {
        val timeline = LiveCaptionTimeline()
        timeline.append(0L, listOf(CaptionSegment(500L, 900L, "hallucination")), 400L)

        timeline.append(400L, emptyList(), 1_000L)

        assertEquals(emptyList<CaptionSegment>(), timeline.snapshot())
    }
}
