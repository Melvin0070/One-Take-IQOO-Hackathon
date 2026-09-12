package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSuggestionTest {
    @Test
    fun timedFillersAreSuggestedButHinglishAndLexicalWordsAreNot() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "um hum like so uh",
                    words = listOf(
                        word(1_000, 1_320, "um"),
                        word(1_500, 1_900, "hum"),
                        word(2_000, 2_400, "like"),
                        word(2_500, 2_900, "so"),
                        word(3_000, 3_320, "uh"),
                    ),
                ),
            ),
        )

        assertEquals(2, suggestions.size)
        assertEquals(SpeechSuggestion.Kind.FILLER, suggestions[0].kind)
        assertEquals("um", suggestions[0].excerpt)
        assertEquals(SpeechSuggestion.Kind.FILLER, suggestions[1].kind)
        assertEquals("uh", suggestions[1].excerpt)
    }

    @Test
    fun adjacentRepeatedWordSuggestsOnlyTheDiscardedFirstWord() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "the the camera",
                    words = listOf(
                        word(1_000, 1_300, "the"),
                        word(1_550, 1_850, "the"),
                        word(2_000, 2_400, "camera"),
                    ),
                ),
            ),
        )

        assertEquals(1, suggestions.size)
        assertEquals(SpeechSuggestion.Kind.REPEATED_WORD, suggestions.single().kind)
        assertEquals(1_000L, suggestions.single().startSample)
        assertEquals(1_300L, suggestions.single().endSample)
    }

    @Test
    fun repeatedPrefixSuggestsTheFirstAttemptAndLeavesReplacementUntouched() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "we test this we test that",
                    words = listOf(
                        word(1_000, 1_250, "we"),
                        word(1_400, 1_800, "test"),
                        word(1_950, 2_200, "this"),
                        word(2_850, 3_100, "we"),
                        word(3_250, 3_650, "test"),
                        word(3_800, 4_100, "that"),
                    ),
                ),
            ),
        )

        assertEquals(1, suggestions.size)
        assertEquals(SpeechSuggestion.Kind.RESTART, suggestions.single().kind)
        assertEquals(1_000L, suggestions.single().startSample)
        assertEquals(2_200L, suggestions.single().endSample)
        assertTrue(suggestions.single().excerpt.contains("we test this"))
    }

    @Test
    fun lowConfidenceAndLegacyCaptionTextAreIgnoredWithoutInterpolation() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                Caption(0, 5_000, "um um"),
                caption(
                    text = "um",
                    words = listOf(
                        word(6_000, 6_300, "um", confidence = 0.64f),
                    ),
                ),
            ),
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun maxGapBreaksRepeatedWordAndStableIdsDoNotDependOnListIdentity() {
        val captions = listOf(
            caption(
                text = "word word",
                words = listOf(
                    word(1_000, 1_300, "word"),
                    word(
                        1_300 + SpeechSuggestionDetector.MAX_GAP_SAMPLES + 1,
                        15_000,
                        "word",
                    ),
                ),
            ),
        )

        val first = SpeechSuggestionDetector().detect(captions)
        val second = SpeechSuggestionDetector().detect(captions.map { it.copy() })

        assertTrue(first.isEmpty())
        assertEquals(first, second)
    }

    @Test
    fun overlappingSuggestionsAreCollapsedToOneStableInterval() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "word word",
                    words = listOf(
                        word(1_000, 1_300, "word"),
                        word(1_500, 1_800, "word"),
                    ),
                ),
            ),
        )

        assertEquals(1, suggestions.size)
        assertEquals(SpeechSuggestion.Kind.REPEATED_WORD, suggestions.single().kind)
        assertEquals(suggestions.single().id, SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "word word",
                    words = listOf(
                        word(1_000, 1_300, "word"),
                        word(1_500, 1_800, "word"),
                    ),
                ),
            ),
        ).single().id)
    }

    @Test
    fun repeatedFillerDoesNotSuggestCuttingTheReplacementWord() {
        val suggestions = SpeechSuggestionDetector().detect(
            listOf(
                caption(
                    text = "um um",
                    words = listOf(
                        word(1_000, 1_300, "um"),
                        word(1_500, 1_800, "um"),
                    ),
                ),
            ),
        )

        assertEquals(1, suggestions.size)
        assertEquals(SpeechSuggestion.Kind.REPEATED_WORD, suggestions.single().kind)
        assertEquals(1_000L, suggestions.single().startSample)
        assertEquals(1_300L, suggestions.single().endSample)
    }

    @Test
    fun transcriptWordsValidateTimingAndConfidence() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptWord(100, 100, "word", 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptWord(100, 200, "word", Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptWord(100, 200, "word", 1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Caption(
                0,
                1_000,
                "word",
                listOf(word(900, 1_100, "word")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Caption(
                0,
                1_000,
                "word two",
                listOf(word(700, 800, "word"), word(750, 900, "two")),
            )
        }
    }

    @Test
    fun engineSnapshotsCopyTranscriptWords() {
        val words = mutableListOf(word(1_000, 1_400, "hello"))
        val captions = mutableListOf(Caption(1_000, 2_000, "hello", words))
        val engine = EditingEngine("words")
        engine.submit(2_000, Change.SourceFinalized("video", 10_000, VideoAnchor(0, 0)))
        engine.submit(2_000, Change.CaptionsReplaced(captions))

        words += word(1_500, 1_900, "mutated")
        val snapshot = engine.snapshot()

        assertEquals(1, snapshot.captions!!.single().words.size)
        @Suppress("UNCHECKED_CAST")
        val snapshotWords = snapshot.captions.single().words as MutableList<TranscriptWord>
        assertThrows(UnsupportedOperationException::class.java) {
            snapshotWords += word(1_500, 1_900, "cannot mutate")
        }
    }

    @Test
    fun mappingAWordCompleteCaptionDropsCutWordsAndRebuildsText() {
        val words = listOf(
            word(1_000, 1_400, "hello"),
            word(1_600, 1_900, "um"),
            word(2_100, 2_500, "world"),
        )
        val plan = EditPlan(
            durationSamples = 20_000,
            cuts = listOf(Cut("filler", 1_500, 2_000, "filler")),
        )

        val mapped = plan.mapCaptions(listOf(Caption(500, 3_000, "hello um world", words)))

        assertEquals(2, mapped.size)
        assertEquals("hello", mapped[0].text)
        assertEquals(listOf("hello"), mapped[0].words.map { it.text })
        assertEquals("world", mapped[1].text)
        assertEquals(listOf("world"), mapped[1].words.map { it.text })
        assertEquals(1_600L, mapped[1].words.single().startSample)
    }

    @Test
    fun partialWordEvidenceKeepsLegacyTextButClearsStaleWords() {
        val plan = EditPlan(
            durationSamples = 20_000,
            cuts = listOf(Cut("pause", 1_500, 2_000, "silence")),
        )
        val mapped = plan.mapCaptions(
            listOf(
                Caption(
                    500,
                    3_000,
                    "hello um world",
                    listOf(word(1_000, 1_400, "hello")),
                ),
            ),
        )

        assertEquals(2, mapped.size)
        assertTrue(mapped.all { it.words.isEmpty() })
        assertEquals("hello um world", mapped[0].text)
        assertEquals("hello um world", mapped[1].text)
    }

    private fun word(
        start: Long,
        end: Long,
        text: String,
        confidence: Float = 0.95f,
    ): TranscriptWord = TranscriptWord(start, end, text, confidence)

    private fun caption(text: String, words: List<TranscriptWord>): Caption = Caption(
        startSample = words.first().startSample,
        endSample = words.last().endSample,
        text = text,
        words = words,
    )
}
