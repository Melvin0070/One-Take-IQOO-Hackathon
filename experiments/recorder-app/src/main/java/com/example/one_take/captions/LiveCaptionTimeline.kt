package com.example.one_take.captions

import java.util.Locale

/**
 * Merges the relative timestamps returned by overlapping live transcription windows.
 *
 * A segment is visible from [snapshot] only after its absolute end is at or before the
 * most recent commit horizon.  Segments past that horizon remain pending so a later,
 * overlapping window can supply the missing tail.  Once a segment is committed its
 * interval and text are stable for the lifetime of this instance.
 */
internal class LiveCaptionTimeline {
    private val committed = ArrayList<CaptionSegment>()
    private val pending = ArrayList<CaptionSegment>()
    private var commitHorizonMs = Long.MIN_VALUE

    /** End of the last committed segment, or zero before the first segment is committed. */
    val committedThroughMs: Long
        get() = committed.lastOrNull()?.endMs ?: 0L

    /**
     * Adds segments whose timestamps are relative to [windowStartMs].
     *
     * [commitBeforeMs] is an absolute recording timestamp.  Only segments ending at or
     * before that timestamp are moved from the pending queue into the committed timeline.
     * Invalid or empty model output is ignored so one bad inference window cannot break a
     * recording.
     */
    fun append(
        windowStartMs: Long,
        segments: List<CaptionSegment>,
        commitBeforeMs: Long,
    ) {
        require(windowStartMs >= 0L) { "windowStartMs must be non-negative" }
        require(commitBeforeMs >= 0L) { "commitBeforeMs must be non-negative" }

        commitHorizonMs = maxOf(commitHorizonMs, commitBeforeMs)
        val absoluteSegments = segments.mapNotNull { toAbsolute(windowStartMs, it) }
        // Callers re-transcribe the entire audio suffix from windowStartMs. Its new
        // hypothesis replaces all uncommitted text there, including a shortened or
        // omitted tail; retaining the older tail would resurrect rejected words.
        pending.removeAll { it.endMs > windowStartMs }
        if (absoluteSegments.isEmpty()) {
            // An empty result from a later overlapping window is useful evidence that
            // pending text in the newly safe prefix was a hallucinated hypothesis.  Do
            // not let it survive until a later horizon commits it.
            if (segments.isEmpty()) {
                pending.removeAll { it.startMs < commitBeforeMs }
            }
            commitEligible()
            return
        }

        for (absolute in absoluteSegments) {
            // Within this hypothesis, discard candidates fully covered by a later
            // segment. Partially overlapping segments are normalized at commit time.
            pending.removeAll { old ->
                absolute.startMs <= old.startMs && absolute.endMs >= old.endMs
            }
            if (absolute.endMs <= committedThroughMs) {
                continue
            }
            if (pending.none { it == absolute }) {
                pending += absolute
            }
        }

        commitEligible()
    }

    /** Returns committed captions in chronological, non-overlapping order. */
    fun snapshot(): List<CaptionSegment> = committed.toList()

    private fun toAbsolute(windowStartMs: Long, segment: CaptionSegment): CaptionSegment? {
        if (segment.startMs < 0L || segment.endMs <= segment.startMs) {
            return null
        }
        val text = segment.text.trim()
        if (text.isEmpty()) {
            return null
        }
        val absoluteStart = safeAdd(windowStartMs, segment.startMs) ?: return null
        val absoluteEnd = safeAdd(windowStartMs, segment.endMs) ?: return null
        if (absoluteEnd <= absoluteStart) {
            return null
        }
        return CaptionSegment(
            startMs = absoluteStart,
            endMs = absoluteEnd,
            text = text,
            words = if (segment.hasReliableWordEvidence()) {
                segment.words.shiftAndBound(windowStartMs, absoluteStart, absoluteEnd)
            } else {
                emptyList()
            },
        )
    }

    private fun commitEligible() {
        if (pending.isEmpty() || commitHorizonMs == Long.MIN_VALUE) {
            return
        }

        // The earliest-ending candidate establishes the next stable boundary.  A later
        // overlapping window can then be trimmed against that boundary without producing
        // a second copy of its prefix.
        pending.sortWith(compareBy<CaptionSegment> { it.endMs }.thenBy { it.startMs })
        val stillPending = ArrayList<CaptionSegment>(pending.size)
        for (candidate in pending) {
            val normalized = normalizeAgainstCommitted(candidate) ?: continue
            if (normalized.endMs <= committedThroughMs) {
                continue
            }
            if (normalized.endMs <= commitHorizonMs) {
                appendCommitted(normalized)
            } else {
                stillPending += normalized
            }
        }
        pending.clear()
        pending += stillPending
    }

    private fun normalizeAgainstCommitted(candidate: CaptionSegment): CaptionSegment? {
        val cursor = committedThroughMs
        if (candidate.endMs <= cursor) {
            return null
        }
        if (candidate.startMs >= cursor) {
            return candidate
        }

        // This is an actual temporal overlap with already committed text.  Remove only
        // matching words at that boundary.  A candidate beginning exactly at the cursor
        // is left untouched so a legitimate later repetition remains visible.
        val previousText = committed.takeLast(4).joinToString(" ") { it.text }
        val prefixWords = candidate.text.words()
        val previousWords = previousText.words()
        var repeatedWords = longestRepeatedPrefix(previousWords, prefixWords)
        // A revised window can insert a filler before re-recognizing an earlier phrase.
        // Require at least two matching words before skipping such a leading token.
        for (skip in 1..minOf(2, prefixWords.size - 1)) {
            val matched = longestRepeatedPrefix(previousWords, prefixWords.drop(skip))
            if (matched >= 2) repeatedWords = maxOf(repeatedWords, skip + matched)
        }
        val remainingText = prefixWords.drop(repeatedWords).joinToString(" ")
        if (remainingText.isBlank()) {
            return null
        }

        val start = cursor.coerceAtMost(candidate.endMs)
        if (candidate.endMs <= start) {
            return null
        }
        // The text no longer has a one-to-one relationship with the model output.  Keeping
        // the old word timings here would make filler detection point at the wrong words.
        return CaptionSegment(start, candidate.endMs, remainingText)
    }

    private fun appendCommitted(candidate: CaptionSegment) {
        val cursor = committedThroughMs
        if (candidate.endMs <= cursor) {
            return
        }
        val normalized = if (candidate.startMs < cursor) {
            // Clipping a candidate changes the relationship between its display text and
            // model timings.  Preserve the caption boundary but drop uncertain evidence.
            candidate.copy(startMs = cursor, words = emptyList())
        } else {
            candidate
        }
        if (normalized.endMs <= normalized.startMs) {
            return
        }
        committed += normalized
    }

    private fun longestRepeatedPrefix(previous: List<String>, candidate: List<String>): Int {
        if (previous.isEmpty() || candidate.isEmpty()) {
            return 0
        }
        val maximum = minOf(previous.size, candidate.size)
        for (count in maximum downTo 1) {
            val previousStart = previous.size - count
            if ((0 until count).all { index ->
                    normalizeWord(previous[previousStart + index]) == normalizeWord(candidate[index])
                }
            ) {
                return count
            }
        }
        return 0
    }

    private fun normalizeWord(word: String): String = word
        .lowercase(Locale.ROOT)
        .trim { character -> !character.isLetterOrDigit() }

    private fun String.words(): List<String> = trim()
        .split(WHITESPACE)
        .filter(String::isNotBlank)

    private fun safeAdd(first: Long, second: Long): Long? {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return null
        }
        return first + second
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
