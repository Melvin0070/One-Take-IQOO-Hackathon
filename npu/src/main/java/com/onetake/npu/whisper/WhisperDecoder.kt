package com.onetake.npu.whisper

import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException

/** Supplies one decoder graph invocation and owns its QNN self-cache state. */
fun interface WhisperGraphTokens {
    /**
     * Feeds one token at [position] and returns logits for the next token.
     *
     * The Android adapter updates the 199-slot self cache and attention mask
     * before each call, then returns the graph's FP16 logits as float values.
     */
    fun next(token: Int, position: Int): FloatArray
}

enum class WhisperTask {
    TRANSCRIBE,
    TRANSLATE,
}

/** Options for one 30-second Whisper decoding window. */
data class WhisperDecodeOptions(
    val language: String? = null,
    val task: WhisperTask = WhisperTask.TRANSCRIBE,
    val timestamps: Boolean = true,
    val windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS,
    val maxDecoderCalls: Int = MAX_DECODER_CALLS,
    val maxInitialTimestampMs: Long = DEFAULT_MAX_INITIAL_TIMESTAMP_MS,
) {
    val normalizedLanguage: String? = language?.let(WhisperTokens::normalizeLanguage)

    init {
        require(language == null || normalizedLanguage != null) {
            "Unsupported Whisper language: $language"
        }
        require(windowDurationMs in 1L..MAX_WINDOW_DURATION_MS) {
            "Whisper window duration must be between 1 and $MAX_WINDOW_DURATION_MS ms"
        }
        require(maxDecoderCalls in 1..MAX_DECODER_CALLS) {
            "Whisper decoder call limit must be between 1 and $MAX_DECODER_CALLS"
        }
        require(maxInitialTimestampMs in 0L..windowDurationMs) {
            "Whisper initial timestamp limit must be between 0 and $windowDurationMs ms"
        }
    }

    companion object {
        const val DEFAULT_WINDOW_DURATION_MS: Long = 30_000L
        const val MAX_WINDOW_DURATION_MS: Long = 30_000L
        /** Matches OpenAI Whisper's one-second initial timestamp constraint. */
        const val DEFAULT_MAX_INITIAL_TIMESTAMP_MS: Long = 1_000L
        /** The exported decoder graph exposes positions 0 through 199. */
        const val MAX_DECODER_CALLS: Int = 200
    }
}

/** A segment with model-provided 20 ms timestamp resolution. */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        require(startMs >= 0L) { "Whisper segment start must be non-negative" }
        require(endMs > startMs) { "Whisper segment end must be after its start" }
        require(text.isNotBlank()) { "Whisper segment text must not be blank" }
    }
}

/** Result of one greedy decoder window. */
class WhisperDecodeResult(
    val language: String,
    tokens: List<Int>,
    segments: List<WhisperSegment>,
    val stoppedOnEndOfText: Boolean,
    /** True when the decoder reached its fixed graph-call limit before EOS. */
    val exhausted: Boolean,
    /** True only when every returned text segment has explicit model timestamps. */
    val timingComplete: Boolean,
    val transcriptText: String,
) {
    val tokens: List<Int> = immutableCopy(tokens)
    val segments: List<WhisperSegment> = immutableCopy(segments)

    init {
        require(WhisperTokens.languageToken(language) != null) {
            "Unknown resolved Whisper language: $language"
        }
        require(!(stoppedOnEndOfText && exhausted)) {
            "A Whisper result cannot be both EOS-terminated and exhausted"
        }
        require(transcriptText.isNotBlank() || segments.isEmpty()) {
            "A blank transcript cannot have decoded segments"
        }
    }

    private companion object {
        private fun <T> immutableCopy(values: List<T>): List<T> =
            Collections.unmodifiableList(values.toList())
    }
}

/** A stable failure for malformed logits, unsupported prompts, or exhausted setup. */
class WhisperDecodeException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Cancellation observed between graph steps. */
class WhisperDecodeCancelledException : CancellationException("Whisper decoding was cancelled")

/**
 * Greedy multilingual Whisper decoding around the Qualcomm encoder/decoder graphs.
 *
 * The graph callback is deliberately small: Android owns raw QNN tensors, masks,
 * and cache copies, while this class owns the prompt, suppression, EOS policy,
 * vocabulary conversion, and segment timestamp contract.
 *
 * The decoder does not drop a window from the SOT no-speech probability. The
 * caller must apply its audio/window silence policy before publishing captions;
 * quiet-room threshold parity remains pending validation on the target phone.
 */
class WhisperGreedyDecoder(
    private val vocabulary: WhisperVocabulary,
    private val graph: WhisperGraphTokens,
    private val isCancelled: () -> Boolean = { false },
) {
    /** Runs one decoder window and returns explicit EOS/exhaustion state. */
    fun decode(options: WhisperDecodeOptions = WhisperDecodeOptions()): WhisperDecodeResult {
        val generated = ArrayList<Int>(options.maxDecoderCalls)
        var calls = 0

        fun next(token: Int): FloatArray {
            checkCancelled()
            if (calls >= options.maxDecoderCalls) {
                throw WhisperDecodeException(
                    "Whisper decoder call limit ${options.maxDecoderCalls} was reached before the next graph step",
                )
            }
            val position = calls
            val logits = try {
                graph.next(token, position)
            } catch (error: WhisperDecodeCancelledException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw WhisperDecodeException("Whisper decoder graph failed at position $position", error)
            }
            calls += 1
            checkCancelled()
            if (logits.size < WhisperVocabulary.VOCAB_SIZE) {
                throw WhisperDecodeException(
                    "Whisper decoder returned ${logits.size} logits, expected at least ${WhisperVocabulary.VOCAB_SIZE}",
                )
            }
            return logits
        }

        // The exported graph accepts one token per invocation.  The first three
        // calls therefore play the standard SOT/language/task prompt in order.
        var logits = next(WhisperTokens.START_OF_TRANSCRIPT)
        val language = options.normalizedLanguage ?: detectLanguage(logits)
        logits = next(WhisperTokens.languageToken(language)!!)
        logits = next(
            when (options.task) {
                WhisperTask.TRANSCRIBE -> WhisperTokens.TRANSCRIBE
                WhisperTask.TRANSLATE -> WhisperTokens.TRANSLATE
            },
        )
        if (!options.timestamps) {
            logits = next(WhisperTokens.NO_TIMESTAMPS)
        }

        var stoppedOnEndOfText = false
        while (true) {
            checkCancelled()
            val token = chooseGeneratedToken(
                logits = logits,
                timestamps = options.timestamps,
                generated = generated,
                maxInitialTimestampMs = options.maxInitialTimestampMs,
            )
            generated += token
            if (token == WhisperTokens.END_OF_TEXT) {
                stoppedOnEndOfText = true
                break
            }
            if (calls >= options.maxDecoderCalls) break
            logits = next(token)
        }

        val exhausted = !stoppedOnEndOfText
        val parsed = WhisperTimestampParser.parseWithStatus(
            tokens = generated,
            vocabulary = vocabulary,
            windowDurationMs = options.windowDurationMs,
        )
        val transcriptText = vocabulary.decode(generated).trim()
        return WhisperDecodeResult(
            language = language,
            tokens = generated,
            segments = parsed.segments,
            stoppedOnEndOfText = stoppedOnEndOfText,
            exhausted = exhausted,
            timingComplete = parsed.complete,
            transcriptText = transcriptText,
        )
    }

    private fun detectLanguage(logits: FloatArray): String {
        var bestToken = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (token in WhisperTokens.FIRST_LANGUAGE..WhisperTokens.LAST_LANGUAGE) {
            val value = logits[token]
            if (value.isFinite() && value > bestValue) {
                bestToken = token
                bestValue = value
            }
        }
        if (bestToken < 0) {
            throw WhisperDecodeException("Whisper language logits did not contain a finite language token")
        }
        return WhisperTokens.languageCode(bestToken)
    }

    private fun chooseGeneratedToken(
        logits: FloatArray,
        timestamps: Boolean,
        generated: List<Int>,
        maxInitialTimestampMs: Long,
    ): Int {
        val filtered = logits.copyOf(WhisperVocabulary.VOCAB_SIZE)
        for (token in 0 until WhisperVocabulary.VOCAB_SIZE) {
            if (!isAllowedGeneratedToken(token, timestamps, generated)) {
                filtered[token] = Float.NEGATIVE_INFINITY
            }
        }
        if (timestamps) {
            applyInitialTimestampRule(filtered, generated, maxInitialTimestampMs)
            applyTimestampMassRule(filtered, generated)
        } else if (generated.isEmpty()) {
            // Match OpenAI's suppress_blank option for the no-timestamps path.
            filtered[WhisperTokens.BLANK] = Float.NEGATIVE_INFINITY
            filtered[WhisperTokens.END_OF_TEXT] = Float.NEGATIVE_INFINITY
        }

        var bestToken = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (token in 0 until WhisperVocabulary.VOCAB_SIZE) {
            val value = filtered[token]
            if (value.isFinite() && value > bestValue) {
                bestToken = token
                bestValue = value
            }
        }
        if (bestToken < 0) {
            throw WhisperDecodeException("Whisper decoder logits contained no usable token")
        }
        return bestToken
    }

    private fun isAllowedGeneratedToken(
        token: Int,
        timestamps: Boolean,
        generated: List<Int>,
    ): Boolean {
        if (token == WhisperTokens.BASE_EMPTY) return false
        if (token == WhisperTokens.END_OF_TEXT) return true
        if (WhisperTokens.isTimestamp(token)) {
            if (!timestamps) return false
            val tick = token - WhisperTokens.TIMESTAMP_BEGIN
            val previous = generated.lastOrNull(WhisperTokens::isTimestamp)
                ?.minus(WhisperTokens.TIMESTAMP_BEGIN)
            if (previous != null && tick < previous) return false
            val lastWasTimestamp = generated.lastOrNull()?.let(WhisperTokens::isTimestamp) == true
            val penultimateWasTimestamp = generated.size < 2 ||
                WhisperTokens.isTimestamp(generated[generated.size - 2])
            if (lastWasTimestamp && penultimateWasTimestamp) return false
            return true
        }
        // EOS is the only non-timestamp special token allowed after the prompt.
        if (token >= WhisperTokens.END_OF_TEXT) return false
        if (timestamps && generated.lastOrNull()?.let(WhisperTokens::isTimestamp) == true) {
            val penultimateWasTimestamp = generated.size < 2 ||
                WhisperTokens.isTimestamp(generated[generated.size - 2])
            if (!penultimateWasTimestamp) return false
        }
        return true
    }

    private fun applyInitialTimestampRule(
        logits: FloatArray,
        generated: List<Int>,
        maxInitialTimestampMs: Long,
    ) {
        if (generated.isNotEmpty()) return
        for (token in 0 until WhisperTokens.TIMESTAMP_BEGIN) {
            logits[token] = Float.NEGATIVE_INFINITY
        }
        val lastAllowed = WhisperTokens.TIMESTAMP_BEGIN +
            ((maxInitialTimestampMs + 19L) / 20L).toInt()
        for (token in (lastAllowed + 1) until logits.size) {
            logits[token] = Float.NEGATIVE_INFINITY
        }
    }

    private fun applyTimestampMassRule(logits: FloatArray, generated: List<Int>) {
        if (generated.isEmpty()) return
        val timestampMass = logSumExp(
            logits,
            WhisperTokens.TIMESTAMP_BEGIN,
            WhisperTokens.TIMESTAMP_BEGIN + WhisperTokens.TIMESTAMP_COUNT,
        )
        val maxText = (0 until WhisperTokens.TIMESTAMP_BEGIN)
            .asSequence()
            .map { logits[it] }
            .filter(Float::isFinite)
            .maxOrNull()
            ?: Float.NEGATIVE_INFINITY
        if (timestampMass > maxText) {
            for (token in 0 until WhisperTokens.TIMESTAMP_BEGIN) {
                logits[token] = Float.NEGATIVE_INFINITY
            }
        }
    }

    private fun logSumExp(values: FloatArray, start: Int, end: Int): Float {
        var maximum = Float.NEGATIVE_INFINITY
        for (index in start until end) maximum = maxOf(maximum, values[index])
        if (!maximum.isFinite()) return maximum
        var sum = 0.0
        for (index in start until end) {
            if (values[index].isFinite()) sum += kotlin.math.exp((values[index] - maximum).toDouble())
        }
        return (maximum + kotlin.math.ln(sum).toFloat())
    }

    private fun checkCancelled() {
        if (isCancelled()) throw WhisperDecodeCancelledException()
    }
}

/** The parse result keeps incomplete model timing observable to the caller. */
class WhisperTimestampParseResult(
    segments: List<WhisperSegment>,
    val complete: Boolean,
) {
    val segments: List<WhisperSegment> = Collections.unmodifiableList(segments.toList())
}

/** Converts timestamp tokens into non-overlapping caption segments. */
object WhisperTimestampParser {
    fun parse(
        tokens: List<Int>,
        vocabulary: WhisperVocabulary,
        windowDurationMs: Long = WhisperDecodeOptions.DEFAULT_WINDOW_DURATION_MS,
    ): List<WhisperSegment> = parseWithStatus(tokens, vocabulary, windowDurationMs).segments

    fun parseWithStatus(
        tokens: List<Int>,
        vocabulary: WhisperVocabulary,
        windowDurationMs: Long = WhisperDecodeOptions.DEFAULT_WINDOW_DURATION_MS,
    ): WhisperTimestampParseResult {
        require(windowDurationMs in 1L..WhisperDecodeOptions.MAX_WINDOW_DURATION_MS) {
            "Whisper window duration must be between 1 and ${WhisperDecodeOptions.MAX_WINDOW_DURATION_MS} ms"
        }
        val maximumTick = ((windowDurationMs + 19L) / 20L)
            .coerceIn(1L, WhisperTokens.TIMESTAMP_COUNT.toLong() - 1L)
            .toInt()
        val segments = ArrayList<WhisperSegment>()
        val textTokens = ArrayList<Int>()
        var openStartTick: Int? = null
        var lastTimestampTick: Int? = null
        var complete = true

        fun emit(startTick: Int, endTick: Int) {
            val boundedStart = startTick.coerceIn(0, maximumTick)
            val boundedEnd = endTick.coerceIn(0, maximumTick)
            val text = vocabulary.decode(textTokens).trim()
            textTokens.clear()
            if (text.isBlank() || boundedEnd <= boundedStart) return
            segments += WhisperSegment(
                startMs = minOf(boundedStart.toLong() * 20L, windowDurationMs),
                endMs = minOf(boundedEnd.toLong() * 20L, windowDurationMs),
                text = text,
            )
        }

        for (token in tokens) {
            require(token in 0 until WhisperVocabulary.VOCAB_SIZE) {
                "Whisper token ID $token is outside 0..${WhisperVocabulary.VOCAB_SIZE - 1}"
            }
            if (token == WhisperTokens.END_OF_TEXT) {
                break
            }
            if (WhisperTokens.isTimestamp(token)) {
                val tick = token - WhisperTokens.TIMESTAMP_BEGIN
                if (lastTimestampTick != null && tick < lastTimestampTick!!) {
                    complete = false
                    continue
                }
                lastTimestampTick = tick
                val previous = openStartTick
                if (previous == null) {
                    if (textTokens.isNotEmpty()) {
                        // A timestamp after text does not establish the
                        // missing start boundary.  Keep the text out of the
                        // timed result and report the incomplete hypothesis.
                        complete = false
                        textTokens.clear()
                    }
                    openStartTick = tick
                } else if (tick > previous) {
                    emit(previous, tick)
                    openStartTick = tick
                }
            } else if (token < WhisperTokens.END_OF_TEXT && token != WhisperTokens.BASE_EMPTY) {
                textTokens += token
            }
        }

        if (textTokens.isNotEmpty()) {
            // A trailing text run has no model end timestamp.  Returning a
            // window-wide synthetic boundary would make it look aligned, so
            // leave it observable through complete=false and omit the run.
            complete = false
        }
        if (openStartTick == null && tokens.none(WhisperTokens::isTimestamp)) {
            complete = false
        }
        return WhisperTimestampParseResult(segments, complete)
    }
}

/** IDs and language ordering from the pinned OpenAI multilingual tokenizer. */
object WhisperTokens {
    /** The raw space token used by the pinned multilingual tiktoken asset. */
    const val BLANK: Int = 220
    const val BASE_EMPTY: Int = 50_256
    const val END_OF_TEXT: Int = 50_257
    const val START_OF_TRANSCRIPT: Int = 50_258
    const val FIRST_LANGUAGE: Int = 50_259
    const val LAST_LANGUAGE: Int = 50_357
    const val TRANSLATE: Int = 50_358
    const val TRANSCRIBE: Int = 50_359
    const val START_OF_LM: Int = 50_360
    const val START_OF_PREVIOUS: Int = 50_361
    const val NO_SPEECH: Int = 50_362
    const val NO_TIMESTAMPS: Int = 50_363
    const val TIMESTAMP_BEGIN: Int = 50_364
    const val TIMESTAMP_COUNT: Int = 1_501
    const val VOCAB_SIZE: Int = 51_865

    private val languageCodes = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
        "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
        "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
        "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
        "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
        "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
        "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
        "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su",
    )

    private val languageAliases = mapOf(
        "english" to "en", "chinese" to "zh", "german" to "de", "spanish" to "es",
        "russian" to "ru", "korean" to "ko", "french" to "fr", "japanese" to "ja",
        "portuguese" to "pt", "turkish" to "tr", "polish" to "pl", "catalan" to "ca",
        "dutch" to "nl", "arabic" to "ar", "swedish" to "sv", "italian" to "it",
        "indonesian" to "id", "hindi" to "hi", "finnish" to "fi", "vietnamese" to "vi",
        "hebrew" to "he", "ukrainian" to "uk", "greek" to "el", "malay" to "ms",
        "czech" to "cs", "romanian" to "ro", "danish" to "da", "hungarian" to "hu",
        "tamil" to "ta", "norwegian" to "no", "thai" to "th", "urdu" to "ur",
        "croatian" to "hr", "bulgarian" to "bg", "lithuanian" to "lt", "latin" to "la",
        "maori" to "mi", "malayalam" to "ml", "welsh" to "cy", "slovak" to "sk",
        "telugu" to "te", "persian" to "fa", "latvian" to "lv", "bengali" to "bn",
        "serbian" to "sr", "azerbaijani" to "az", "slovenian" to "sl", "kannada" to "kn",
        "estonian" to "et", "macedonian" to "mk", "breton" to "br", "basque" to "eu",
        "icelandic" to "is", "armenian" to "hy", "nepali" to "ne", "mongolian" to "mn",
        "bosnian" to "bs", "kazakh" to "kk", "albanian" to "sq", "swahili" to "sw",
        "galician" to "gl", "marathi" to "mr", "punjabi" to "pa", "sinhala" to "si",
        "khmer" to "km", "shona" to "sn", "yoruba" to "yo", "somali" to "so",
        "afrikaans" to "af", "occitan" to "oc", "georgian" to "ka", "belarusian" to "be",
        "tajik" to "tg", "sindhi" to "sd", "gujarati" to "gu", "amharic" to "am",
        "yiddish" to "yi", "lao" to "lo", "uzbek" to "uz", "faroese" to "fo",
        "haitian creole" to "ht", "pashto" to "ps", "turkmen" to "tk", "nynorsk" to "nn",
        "maltese" to "mt", "sanskrit" to "sa", "luxembourgish" to "lb", "myanmar" to "my",
        "tibetan" to "bo", "tagalog" to "tl", "malagasy" to "mg", "assamese" to "as",
        "tatar" to "tt", "lingala" to "ln", "hausa" to "ha", "bashkir" to "ba",
        "javanese" to "jw", "sundanese" to "su",
        "burmese" to "my", "valencian" to "ca", "flemish" to "nl", "haitian" to "ht",
        "letzeburgesch" to "lb", "pushto" to "ps", "panjabi" to "pa", "moldavian" to "ro",
        "moldovan" to "ro", "sinhalese" to "si", "castilian" to "es",
    )

    fun normalizeLanguage(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val code = languageAliases[normalized] ?: normalized
        return code.takeIf { it in languageCodes }
    }

    fun languageToken(language: String): Int? = normalizeLanguage(language)?.let {
        FIRST_LANGUAGE + languageCodes.indexOf(it)
    }

    fun languageCode(token: Int): String {
        require(token in FIRST_LANGUAGE..LAST_LANGUAGE) {
            "Token $token is not a Whisper language token"
        }
        return languageCodes[token - FIRST_LANGUAGE]
    }

    fun isTimestamp(token: Int): Boolean =
        token in TIMESTAMP_BEGIN until (TIMESTAMP_BEGIN + TIMESTAMP_COUNT)

    fun specialTokenText(token: Int): String = when {
        token == END_OF_TEXT -> "<|endoftext|>"
        token == START_OF_TRANSCRIPT -> "<|startoftranscript|>"
        token in FIRST_LANGUAGE..LAST_LANGUAGE -> "<|${languageCode(token)}|>"
        token == TRANSLATE -> "<|translate|>"
        token == TRANSCRIBE -> "<|transcribe|>"
        token == START_OF_LM -> "<|startoflm|>"
        token == START_OF_PREVIOUS -> "<|startofprev|>"
        token == NO_SPEECH -> "<|nospeech|>"
        token == NO_TIMESTAMPS -> "<|notimestamps|>"
        isTimestamp(token) -> String.format(
            Locale.ROOT,
            "<|%.2f|>",
            (token - TIMESTAMP_BEGIN) * 0.02f,
        )
        else -> throw IllegalArgumentException("Token $token is not a Whisper special token")
    }
}
