package com.onetake.engine.whisper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperDecoderTest {
    private val vocabulary = testVocabulary()

    @Test
    fun parsesTheOfficialNulDelimitedVocabularyAndReconstructsByteTokens() {
        assertEquals(WhisperVocabulary.BASE_VOCAB_SIZE, vocabulary.size)
        assertEquals("Hello world", vocabulary.decode(listOf(0, 1)))
        assertEquals(byteArrayOf(0).toList(), vocabulary.rawToken(188).toList())
        assertEquals(byteArrayOf().toList(), vocabulary.rawToken(WhisperTokens.BASE_EMPTY).toList())
        assertEquals("<|en|>", vocabulary.decode(listOf(WhisperTokens.FIRST_LANGUAGE), false))
        assertEquals("<|1.00|>", vocabulary.decode(listOf(WhisperTokens.TIMESTAMP_BEGIN + 50), false))
    }

    @Test
    fun autoLanguagePromptGreedilyBuildsTimestampedSegments() {
        val calls = ArrayList<Pair<Int, Int>>()
        val graph = WhisperGraphTokens { token, position ->
            calls += token to position
            logits().apply {
                when (position) {
                    0 -> this[WhisperTokens.FIRST_LANGUAGE] = 5f
                    2 -> this[WhisperTokens.TIMESTAMP_BEGIN + 5] = 10f
                    3 -> this[0] = 9f
                    4 -> this[WhisperTokens.TIMESTAMP_BEGIN + 10] = 10f
                    5 -> this[WhisperTokens.END_OF_TEXT] = 10f
                }
            }
        }

        val result = WhisperGreedyDecoder(vocabulary, graph).decode(
            WhisperDecodeOptions(maxInitialTimestampMs = 1_000L),
        )

        assertEquals("en", result.language)
        assertTrue(result.stoppedOnEndOfText)
        assertFalse(result.exhausted)
        assertTrue(result.timingComplete)
        assertEquals("Hello", result.transcriptText)
        assertEquals(
            listOf(WhisperSegment(100L, 200L, "Hello")),
            result.segments,
        )
        assertEquals(
            listOf(
                WhisperTokens.START_OF_TRANSCRIPT to 0,
                WhisperTokens.FIRST_LANGUAGE to 1,
                WhisperTokens.TRANSCRIBE to 2,
                WhisperTokens.TIMESTAMP_BEGIN + 5 to 3,
                0 to 4,
                WhisperTokens.TIMESTAMP_BEGIN + 10 to 5,
            ),
            calls,
        )
    }

    @Test
    fun timestampMassAndPairRulesSuppressIllegalGreedyChoices() {
        val graph = WhisperGraphTokens { _, position ->
            logits().apply {
                when (position) {
                    0 -> this[WhisperTokens.FIRST_LANGUAGE] = 1f
                // The first generated token must be a timestamp, even
                // though the text logit is larger.
                    2 -> {
                        this[0] = 100f
                        this[WhisperTokens.TIMESTAMP_BEGIN + 2] = 1f
                    }
                    // A timestamp mass larger than the best text logit forces
                    // the next timestamp after the text token.
                    3 -> {
                        this[1] = 1f
                        this[WhisperTokens.TIMESTAMP_BEGIN + 4] = 2f
                        this[WhisperTokens.TIMESTAMP_BEGIN + 5] = 2f
                    }
                    // A timestamp after a text token is allowed.  Once it is
                    // selected, the next text token is suppressed and EOS
                    // wins even when a text logit is larger.
                    4 -> {
                        this[WhisperTokens.TIMESTAMP_BEGIN + 9] = 100f
                    }
                    5 -> {
                        this[0] = 100f
                        this[WhisperTokens.END_OF_TEXT] = 2f
                    }
                }
            }
        }
        val result = WhisperGreedyDecoder(vocabulary, graph).decode()

        assertEquals(
            listOf(
                WhisperTokens.TIMESTAMP_BEGIN + 2,
                1,
                WhisperTokens.TIMESTAMP_BEGIN + 9,
                WhisperTokens.END_OF_TEXT,
            ),
            result.tokens,
        )
        assertTrue(result.timingComplete)
        assertEquals(listOf(WhisperSegment(40L, 180L, "world")), result.segments)
    }

    @Test
    fun noTimestampModeUsesExplicitTextAndReportsUntimedResult() {
        val graph = WhisperGraphTokens { _, position ->
            logits().apply {
                when (position) {
                    0 -> this[WhisperTokens.FIRST_LANGUAGE] = 1f
                    3 -> this[0] = 5f
                    4 -> this[WhisperTokens.END_OF_TEXT] = 5f
                }
            }
        }
        val result = WhisperGreedyDecoder(vocabulary, graph).decode(
            WhisperDecodeOptions(language = "English", timestamps = false),
        )

        assertEquals("en", result.language)
        assertEquals(listOf(0, WhisperTokens.END_OF_TEXT), result.tokens)
        assertEquals("Hello", result.transcriptText)
        assertTrue(result.segments.isEmpty())
        assertFalse(result.timingComplete)
    }

    @Test
    fun exhaustionIsObservableAndDoesNotPretendTrailingTextWasTimed() {
        val graph = WhisperGraphTokens { _, position ->
            logits().apply {
                when (position) {
                    0 -> this[WhisperTokens.FIRST_LANGUAGE] = 1f
                    2 -> this[WhisperTokens.TIMESTAMP_BEGIN] = 5f
                    3 -> this[0] = 5f
                    4 -> this[WhisperTokens.TIMESTAMP_BEGIN + 5] = 5f
                    5 -> this[WhisperTokens.TIMESTAMP_BEGIN + 10] = 5f
                }
            }
        }
        val result = WhisperGreedyDecoder(vocabulary, graph).decode(
            WhisperDecodeOptions(maxDecoderCalls = 6),
        )

        assertTrue(result.exhausted)
        assertFalse(result.stoppedOnEndOfText)
        assertTrue(result.timingComplete)
        assertEquals(listOf(WhisperSegment(0L, 100L, "Hello")), result.segments)
    }

    @Test
    fun finalAllowedGraphLogitsCanTerminateWithEos() {
        val calls = ArrayList<Int>()
        val graph = WhisperGraphTokens { _, position ->
            calls += position
            logits().apply {
                when (position) {
                    2 -> this[WhisperTokens.TIMESTAMP_BEGIN] = 5f
                    3 -> this[WhisperTokens.END_OF_TEXT] = 5f
                }
            }
        }
        val result = WhisperGreedyDecoder(vocabulary, graph).decode(
            WhisperDecodeOptions(language = "en", maxDecoderCalls = 4),
        )

        assertTrue(result.stoppedOnEndOfText)
        assertFalse(result.exhausted)
        assertEquals(
            listOf(WhisperTokens.TIMESTAMP_BEGIN, WhisperTokens.END_OF_TEXT),
            result.tokens,
        )
        assertEquals(listOf(0, 1, 2, 3), calls)
    }

    @Test
    fun parserMarksMissingBoundariesAndDescendingTimestampsIncomplete() {
        val missingStart = WhisperTimestampParser.parseWithStatus(
            listOf(0, WhisperTokens.TIMESTAMP_BEGIN + 5, WhisperTokens.END_OF_TEXT),
            vocabulary,
        )
        assertFalse(missingStart.complete)
        assertTrue(missingStart.segments.isEmpty())

        val missingEnd = WhisperTimestampParser.parseWithStatus(
            listOf(WhisperTokens.TIMESTAMP_BEGIN, 0, WhisperTokens.END_OF_TEXT),
            vocabulary,
        )
        assertFalse(missingEnd.complete)
        assertTrue(missingEnd.segments.isEmpty())

        val descending = WhisperTimestampParser.parseWithStatus(
            listOf(
                WhisperTokens.TIMESTAMP_BEGIN + 5,
                0,
                WhisperTokens.TIMESTAMP_BEGIN + 4,
                WhisperTokens.END_OF_TEXT,
            ),
            vocabulary,
        )
        assertFalse(descending.complete)
        assertTrue(descending.segments.isEmpty())
    }

    @Test
    fun parserClampsExplicitEndToTheActualWindowDuration() {
        val parsed = WhisperTimestampParser.parseWithStatus(
            listOf(
                WhisperTokens.TIMESTAMP_BEGIN,
                0,
                WhisperTokens.TIMESTAMP_BEGIN + 5,
                WhisperTokens.END_OF_TEXT,
            ),
            vocabulary,
            windowDurationMs = 35L,
        )
        assertTrue(parsed.complete)
        assertEquals(listOf(WhisperSegment(0L, 35L, "Hello")), parsed.segments)
    }

    @Test
    fun cancellationFromGraphIsPreservedAndCancellationIsCheckedBetweenSteps() {
        val cancellation = CancellationException("test cancellation")
        var calls = 0
        val graph = WhisperGraphTokens { _, _ ->
            calls += 1
            if (calls == 2) throw cancellation
            logits().apply { this[WhisperTokens.FIRST_LANGUAGE] = 1f }
        }

        val thrown = assertThrows(CancellationException::class.java) {
            WhisperGreedyDecoder(vocabulary, graph).decode()
        }
        assertSame(cancellation, thrown)

        var cancelled = false
        val callback = WhisperGraphTokens { _, _ ->
            cancelled = true
            logits().apply { this[WhisperTokens.FIRST_LANGUAGE] = 1f }
        }
        assertThrows(WhisperDecodeCancelledException::class.java) {
            WhisperGreedyDecoder(vocabulary, callback) { cancelled }.decode()
        }
    }

    @Test
    fun malformedGraphOutputAndTooSmallCallBudgetFailClearly() {
        val shortGraph = WhisperGraphTokens { _, _ -> FloatArray(8) }
        val shortError = assertThrows(WhisperDecodeException::class.java) {
            WhisperGreedyDecoder(vocabulary, shortGraph).decode()
        }
        assertTrue(shortError.message!!.contains("logits"))

        val graph = WhisperGraphTokens { _, position ->
            logits().apply {
                if (position == 0) this[WhisperTokens.FIRST_LANGUAGE] = 1f
            }
        }
        val budgetError = assertThrows(WhisperDecodeException::class.java) {
            WhisperGreedyDecoder(vocabulary, graph).decode(WhisperDecodeOptions(maxDecoderCalls = 2))
        }
        assertTrue(budgetError.message!!.contains("call limit"))
    }

    private fun logits(): FloatArray = FloatArray(WhisperVocabulary.VOCAB_SIZE) { Float.NEGATIVE_INFINITY }

    private fun testVocabulary(): WhisperVocabulary {
        val output = ByteArrayOutputStream()
        repeat(WhisperVocabulary.BASE_VOCAB_SIZE) { tokenId ->
            val token = when (tokenId) {
                0 -> "Hello".toByteArray()
                1 -> " world".toByteArray()
                188 -> byteArrayOf(0)
                WhisperTokens.BLANK -> byteArrayOf(' '.code.toByte())
                else -> byteArrayOf()
            }
            output.write(token)
            if (0.toByte() !in token) output.write(0)
        }
        return WhisperVocabulary.load(ByteArrayInputStream(output.toByteArray()))
    }
}
