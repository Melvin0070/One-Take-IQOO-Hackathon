package com.onetake.engine

import java.util.Locale

/** A review-only signal about a likely self-repair in a transcript. */
data class SpeechSuggestion(
    val id: String,
    val startSample: Long,
    val endSample: Long,
    val kind: Kind,
    val confidence: Float,
    val excerpt: String,
) {
    init {
        require(id.isNotBlank()) { "Speech suggestion id must not be blank" }
        require(startSample >= 0L) { "Speech suggestion start must be non-negative" }
        require(endSample > startSample) {
            "Speech suggestion end must be after its start"
        }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Speech suggestion confidence must be finite and between 0 and 1"
        }
        require(excerpt.isNotBlank()) { "Speech suggestion excerpt must not be blank" }
    }

    enum class Kind {
        FILLER,
        REPEATED_WORD,
        RESTART,
    }
}

/** Convenient public name for callers that prefer a standalone enum type. */
typealias SpeechSuggestionKind = SpeechSuggestion.Kind

/**
 * Finds conservative, review-only transcript signals.
 *
 * The detector intentionally requires recognizer-provided word timings. It
 * never interpolates from a caption interval, applies a cut, or treats a
 * low-confidence word as evidence for a neighboring suggestion.
 */
class SpeechSuggestionDetector(
    private val minimumConfidence: Float = MIN_CONFIDENCE,
    private val maxGapSamples: Long = MAX_GAP_SAMPLES,
    private val maxCandidateSamples: Long = MAX_CANDIDATE_SAMPLES,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f) {
            "Minimum confidence must be finite and between 0 and 1"
        }
        require(maxGapSamples >= 0L) { "Maximum gap must be non-negative" }
        require(maxCandidateSamples > 0L) { "Maximum candidate length must be positive" }
    }

    /** Returns stable, non-overlapping review suggestions in source order. */
    fun detect(captions: List<Caption>): List<SpeechSuggestion> {
        val words = captions
            .flatMap { caption -> caption.words }
            .mapIndexed { index, word -> TimedWord(index, word) }
            .sortedWith(
                compareBy<TimedWord> { it.word.startSample }
                    .thenBy { it.word.endSample }
                    .thenBy { it.index },
            )
        if (words.isEmpty()) return emptyList()

        val candidates = ArrayList<RawSuggestion>()
        FillerLexicon.scan(words.map { it.word }, minimumConfidence).spans.forEach { span ->
            addCandidate(
                candidates,
                RawSuggestion(
                    startIndex = span.startIndex,
                    endIndexExclusive = span.endIndexExclusive,
                    comparedEndIndexExclusive = span.endIndexExclusive,
                    kind = SpeechSuggestion.Kind.FILLER,
                ),
                words,
            )
        }
        words.forEachIndexed { index, timed ->
            if (!timed.eligible(minimumConfidence)) return@forEachIndexed

            val token = canonicalToken(timed.word.text)
            val next = words.getOrNull(index + 1)
            if (next != null &&
                next.eligible(minimumConfidence) &&
                token.isNotEmpty() &&
                canonicalToken(next.word.text) == token &&
                isAdjacent(timed.word, next.word)
            ) {
                addCandidate(
                    candidates,
                    RawSuggestion(
                        startIndex = index,
                        endIndexExclusive = index + 1,
                        comparedEndIndexExclusive = index + 2,
                        kind = SpeechSuggestion.Kind.REPEATED_WORD,
                    ),
                    words,
                )
            }
        }

        addRestartCandidates(words, candidates)

        return selectNonOverlapping(candidates, words)
    }

    private fun addRestartCandidates(
        words: List<TimedWord>,
        candidates: MutableList<RawSuggestion>,
    ) {
        for (startIndex in words.indices) {
            if (!words[startIndex].eligible(minimumConfidence)) continue

            for (prefixLength in MIN_PREFIX_WORDS..MAX_PREFIX_WORDS) {
                val firstPrefixEnd = startIndex + prefixLength
                if (firstPrefixEnd >= words.size) continue
                if (!allEligible(words, startIndex, firstPrefixEnd)) continue

                var secondStartIndex = firstPrefixEnd
                while (secondStartIndex + prefixLength <= words.size) {
                    val firstAttemptEndIndex = secondStartIndex
                    if (!allEligible(words, secondStartIndex, secondStartIndex + prefixLength)) {
                        secondStartIndex++
                        continue
                    }
                    if (!sameTokens(
                            words,
                            startIndex,
                            secondStartIndex,
                            prefixLength,
                        )
                    ) {
                        secondStartIndex++
                        continue
                    }

                    val firstAttemptStart = words[startIndex].word.startSample
                    val firstAttemptEnd = words[firstAttemptEndIndex - 1].word.endSample
                    val replacementStart = words[secondStartIndex].word.startSample
                    val candidateLength = firstAttemptEnd - firstAttemptStart
                    val replacementGap = replacementStart - firstAttemptEnd
                    if (replacementGap >= 0L &&
                        replacementGap <= maxGapSamples &&
                        candidateLength <= maxCandidateSamples &&
                        allEligible(words, startIndex, firstAttemptEndIndex) &&
                        allAdjacent(words, startIndex, firstAttemptEndIndex) &&
                        allAdjacent(
                            words,
                            secondStartIndex,
                            secondStartIndex + prefixLength,
                        )
                    ) {
                        addCandidate(
                            candidates,
                            RawSuggestion(
                                startIndex = startIndex,
                                endIndexExclusive = firstAttemptEndIndex,
                                comparedEndIndexExclusive =
                                    secondStartIndex + prefixLength,
                                kind = SpeechSuggestion.Kind.RESTART,
                            ),
                            words,
                        )
                        // The earliest matching prefix is the most local and
                        // conservative interpretation of the restart.
                        break
                    }
                    secondStartIndex++
                }
            }
        }
    }

    private fun selectNonOverlapping(
        candidates: List<RawSuggestion>,
        words: List<TimedWord>,
    ): List<SpeechSuggestion> {
        val selected = ArrayList<SpeechSuggestion>()
        val protectedReplacementIntervals = candidates
            .asSequence()
            .filter { it.kind != SpeechSuggestion.Kind.FILLER }
            .mapNotNull { candidate ->
                val startIndex = candidate.endIndexExclusive
                val endIndex = candidate.comparedEndIndexExclusive
                if (endIndex <= startIndex || endIndex > words.size) {
                    null
                } else {
                    SampleRange(
                        startSample = words[startIndex].word.startSample,
                        endSample = words[endIndex - 1].word.endSample,
                    )
                }
            }
            .toList()
        val ordered = candidates
            .filter { candidate ->
                candidate.kind != SpeechSuggestion.Kind.FILLER ||
                    protectedReplacementIntervals.none { protected ->
                        !rangesAreDisjoint(
                            candidate.startSample(words),
                            candidate.endSample(words),
                            protected.startSample,
                            protected.endSample,
                        )
                    }
            }
            .distinctBy { it.stableKey(words) }
            .sortedWith(
                compareBy<RawSuggestion> { it.startSample(words) }
                    .thenByDescending { it.lengthSamples(words) }
                    .thenBy { kindPriority(it.kind) }
                    .thenBy { it.endSample(words) }
                    .thenBy { it.kind.name },
            )

        ordered.forEach { candidate ->
            val suggestion = candidate.toSuggestion(words)
            if (selected.none { existing -> !isDisjoint(existing, suggestion) }) {
                selected += suggestion
            }
        }
        return selected.sortedWith(compareBy<SpeechSuggestion> { it.startSample }.thenBy { it.endSample })
    }

    private fun addCandidate(
        candidates: MutableList<RawSuggestion>,
        candidate: RawSuggestion,
        words: List<TimedWord>,
    ) {
        val start = candidate.startSample(words)
        val end = candidate.endSample(words)
        if (end > start && end - start <= maxCandidateSamples) {
            candidates += candidate
        }
    }

    private fun isAdjacent(first: TranscriptWord, second: TranscriptWord): Boolean =
        second.startSample >= first.endSample &&
            second.startSample - first.endSample <= maxGapSamples

    private fun allAdjacent(words: List<TimedWord>, start: Int, endExclusive: Int): Boolean =
        (start until endExclusive - 1).all { index ->
            isAdjacent(words[index].word, words[index + 1].word)
        }

    private fun allEligible(words: List<TimedWord>, start: Int, endExclusive: Int): Boolean =
        (start until endExclusive).all { words[it].eligible(minimumConfidence) }

    private fun sameTokens(
        words: List<TimedWord>,
        firstStart: Int,
        secondStart: Int,
        length: Int,
    ): Boolean = (0 until length).all { offset ->
        val firstToken = canonicalToken(words[firstStart + offset].word.text)
        firstToken.isNotEmpty() && firstToken ==
            canonicalToken(words[secondStart + offset].word.text)
    }

    private fun isDisjoint(first: SpeechSuggestion, second: SpeechSuggestion): Boolean =
        first.endSample <= second.startSample || second.endSample <= first.startSample

    private fun rangesAreDisjoint(
        firstStart: Long,
        firstEnd: Long,
        secondStart: Long,
        secondEnd: Long,
    ): Boolean = firstEnd <= secondStart || secondEnd <= firstStart

    private fun kindPriority(kind: SpeechSuggestion.Kind): Int = when (kind) {
        SpeechSuggestion.Kind.RESTART -> 0
        SpeechSuggestion.Kind.REPEATED_WORD -> 1
        SpeechSuggestion.Kind.FILLER -> 2
    }

    private data class TimedWord(
        val index: Int,
        val word: TranscriptWord,
    ) {
        fun eligible(minimumConfidence: Float): Boolean = confidence >= minimumConfidence

        private val confidence: Float
            get() = word.confidence
    }

    private data class RawSuggestion(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val comparedEndIndexExclusive: Int,
        val kind: SpeechSuggestion.Kind,
    ) {
        fun startSample(words: List<TimedWord>): Long = words[startIndex].word.startSample

        fun endSample(words: List<TimedWord>): Long =
            words[endIndexExclusive - 1].word.endSample

        fun lengthSamples(words: List<TimedWord>): Long = endSample(words) - startSample(words)

        fun stableKey(words: List<TimedWord>): String =
            "${kind.name}:${startSample(words)}:${endSample(words)}"

        fun toSuggestion(words: List<TimedWord>): SpeechSuggestion {
            val start = startSample(words)
            val end = endSample(words)
            val evidenceEnd = comparedEndIndexExclusive.coerceAtMost(words.size)
            val confidence = (startIndex until evidenceEnd)
                .minOf { words[it].word.confidence }
            val excerpt = (startIndex until endIndexExclusive)
                .joinToString(" ") { words[it].word.text }
            val kindId = kind.name.lowercase(Locale.ROOT)
            return SpeechSuggestion(
                id = "suggestion-$kindId-$start-$end",
                startSample = start,
                endSample = end,
                kind = kind,
                confidence = confidence,
                excerpt = excerpt,
            )
        }
    }

    companion object {
        const val MIN_CONFIDENCE: Float = 0.65f
        const val MAX_GAP_SAMPLES: Long = 12_800L
        const val MAX_CANDIDATE_SAMPLES: Long = 64_000L
        const val MIN_PREFIX_WORDS: Int = 2
        const val MAX_PREFIX_WORDS: Int = 4

        /** Convenience entry point for callers without detector configuration. */
        fun detect(captions: List<Caption>): List<SpeechSuggestion> =
            SpeechSuggestionDetector().detect(captions)

        private fun canonicalToken(text: String): String = text
            .trim()
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }

    private fun canonicalToken(text: String): String = Companion.canonicalToken(text)
}
