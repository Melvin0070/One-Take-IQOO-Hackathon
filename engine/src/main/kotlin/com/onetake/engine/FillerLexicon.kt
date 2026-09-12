package com.onetake.engine

import java.util.Locale

/** A filler found in recognizer-timed words. [text] is the lexicon entry, not the recognized spelling. */
data class FillerMatch(
    val startSample: Long,
    val endSample: Long,
    val text: String,
)

/**
 * English filler lexicon shared by live tracking and post-hoc review, so both find the same fillers
 * in the same words.
 *
 * Hesitation sounds ("um", "uh", "erm", "hmm", including stretched spellings such as "ummm") always
 * count. "like", "so", "basically" and "you know" are ordinary words too, so they count only when set
 * off on both sides by a pause or a hesitation sound: "I like it" and "so that" never match, at the
 * cost of missing fillers spoken without a pause. Stretching does not make them fillers, because
 * "sooo good" is emphasis.
 *
 * Like [SpeechSuggestionDetector], matching needs recognizer word timings, and a low-confidence word
 * is never evidence, not even as the neighbor that sets a marker off.
 */
object FillerLexicon {
    /** The shortest gap between two words that counts as a pause. */
    const val MIN_PAUSE_SAMPLES: Long = 3_200L

    private const val MAX_FILLER_SAMPLES: Long = SpeechSuggestionDetector.MAX_CANDIDATE_SAMPLES

    /** Keyed by the lowercase letters of a word with repeated letters collapsed. */
    private val HESITATIONS = mapOf("um" to "um", "uhm" to "um", "uh" to "uh", "erm" to "erm", "hm" to "hmm")

    private val MARKERS = listOf(
        listOf("you", "know") to "you know",
        listOf("yknow") to "you know",
        listOf("basically") to "basically",
        listOf("like") to "like",
        listOf("so") to "so",
    ).sortedByDescending { (tokens, _) -> tokens.size }

    /** Returns non-overlapping fillers in source order. */
    fun find(
        words: List<TranscriptWord>,
        minimumConfidence: Float = SpeechSuggestionDetector.MIN_CONFIDENCE,
    ): List<FillerMatch> = scan(inSourceOrder(words), minimumConfidence).spans.map { it.match }

    internal fun inSourceOrder(words: List<TranscriptWord>): List<TranscriptWord> =
        words.sortedWith(compareBy<TranscriptWord> { it.startSample }.thenBy { it.endSample })

    /**
     * Scans [words], already in [inSourceOrder], starting at [fromIndex]; earlier words are only
     * context. Stops at the first marker whose decision needs a word after the last one.
     *
     * The end of the words is deliberately not a pause: a live segment cannot know whether speech
     * continues, and post-hoc review applies the same rule so both paths agree.
     */
    internal fun scan(words: List<TranscriptWord>, minimumConfidence: Float, fromIndex: Int = 0): FillerScan {
        require(fromIndex in 0..words.size) { "Scan must start within the words" }
        val spans = ArrayList<FillerSpan>()
        var index = fromIndex
        while (index < words.size) {
            val hesitation = hesitation(words[index], minimumConfidence)
            if (hesitation != null) {
                spans += span(words, index, index + 1, hesitation)
                index++
                continue
            }
            when (val marker = marker(words, index, minimumConfidence)) {
                MarkerResult.Undecided -> return FillerScan(spans, index)
                MarkerResult.NoMatch -> index++
                is MarkerResult.Match -> {
                    spans += marker.span
                    index = marker.span.endIndexExclusive
                }
            }
        }
        return FillerScan(spans, words.size)
    }

    private fun marker(words: List<TranscriptWord>, index: Int, minimumConfidence: Float): MarkerResult {
        for ((tokens, entry) in MARKERS) {
            val available = minOf(tokens.size, words.size - index)
            val prefixMatches = (0 until available).all { offset ->
                canonical(words[index + offset].text) == tokens[offset]
            }
            if (!prefixMatches) continue
            val endExclusive = index + available
            val spoken = (index until endExclusive).all { eligible(words[it], minimumConfidence) } &&
                (index until endExclusive - 1).all { gap(words[it], words[it + 1]) in 0 until MIN_PAUSE_SAMPLES } &&
                words[endExclusive - 1].endSample - words[index].startSample <= MAX_FILLER_SAMPLES
            if (!spoken || !setOff(words, index - 1, index, minimumConfidence)) continue
            if (available < tokens.size || endExclusive == words.size) return MarkerResult.Undecided
            if (setOff(words, endExclusive, endExclusive - 1, minimumConfidence)) {
                return MarkerResult.Match(span(words, index, endExclusive, entry))
            }
        }
        return MarkerResult.NoMatch
    }

    /** Whether the neighbor at [neighborIndex] of the marker word at [markerIndex] sets the marker off. */
    private fun setOff(
        words: List<TranscriptWord>,
        neighborIndex: Int,
        markerIndex: Int,
        minimumConfidence: Float,
    ): Boolean {
        if (neighborIndex < 0) return true
        val neighbor = words[neighborIndex]
        if (!eligible(neighbor, minimumConfidence)) return false
        val marker = words[markerIndex]
        val pause = if (neighborIndex < markerIndex) gap(neighbor, marker) else gap(marker, neighbor)
        return pause >= MIN_PAUSE_SAMPLES || hesitation(neighbor, minimumConfidence) != null
    }

    private fun hesitation(word: TranscriptWord, minimumConfidence: Float): String? {
        if (!eligible(word, minimumConfidence) || word.endSample - word.startSample > MAX_FILLER_SAMPLES) return null
        return HESITATIONS[collapseRepeats(canonical(word.text))]
    }

    private fun span(words: List<TranscriptWord>, start: Int, endExclusive: Int, entry: String) = FillerSpan(
        startIndex = start,
        endIndexExclusive = endExclusive,
        match = FillerMatch(words[start].startSample, words[endExclusive - 1].endSample, entry),
    )

    private fun eligible(word: TranscriptWord, minimumConfidence: Float) = word.confidence >= minimumConfidence

    private fun gap(first: TranscriptWord, second: TranscriptWord) = second.startSample - first.endSample

    private fun canonical(text: String): String = text.lowercase(Locale.ROOT).filter(Char::isLetter)

    private fun collapseRepeats(token: String): String =
        token.filterIndexed { index, char -> index == 0 || token[index - 1] != char }

    private sealed interface MarkerResult {
        data object NoMatch : MarkerResult
        data object Undecided : MarkerResult
        data class Match(val span: FillerSpan) : MarkerResult
    }
}

internal data class FillerSpan(
    val startIndex: Int,
    val endIndexExclusive: Int,
    val match: FillerMatch,
)

/** [resumeIndex] is the first word whose filler decision awaits later words. */
internal data class FillerScan(
    val spans: List<FillerSpan>,
    val resumeIndex: Int,
)
