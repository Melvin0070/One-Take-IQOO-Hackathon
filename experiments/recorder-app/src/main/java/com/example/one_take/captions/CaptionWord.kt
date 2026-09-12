package com.example.one_take.captions

/** A model-timed word inside a caption segment. */
internal data class CaptionWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float,
)

/** Returns whether the timing list still describes exactly the displayed caption text. */
internal fun CaptionSegment.hasReliableWordEvidence(): Boolean {
    if (words.isEmpty()) return true
    val displayed = text.trim().split(WHITESPACE).filter(String::isNotBlank).joinToString(" ")
    val timed = words.joinToString(" ") { it.text.trim() }.trim()
    return displayed == timed
}

/**
 * Moves model timings onto another clock while keeping them inside the owning segment.
 *
 * A word is discarded when its timing or confidence is not usable.  The caller can then
 * safely retain the caption text without pretending that a missing word timing was inferred.
 */
internal fun List<CaptionWord>.shiftAndBound(
    offsetMs: Long,
    boundStartMs: Long,
    boundEndMs: Long,
): List<CaptionWord> {
    if (isEmpty() || boundStartMs < 0L || boundEndMs <= boundStartMs) {
        return emptyList()
    }

    val shifted = ArrayList<CaptionWord>(size)
    for (word in this) {
        if (word.startMs < 0L || word.endMs <= word.startMs ||
            word.text.isBlank() || !word.confidence.isFinite() ||
            word.confidence < 0f || word.confidence > 1f
        ) {
            continue
        }
        val start = safeAdd(word.startMs, offsetMs) ?: continue
        val end = safeAdd(word.endMs, offsetMs) ?: continue
        val boundedStart = start.coerceIn(boundStartMs, boundEndMs)
        val boundedEnd = end.coerceIn(boundStartMs, boundEndMs)
        if (boundedEnd <= boundedStart) {
            continue
        }
        shifted += word.copy(startMs = boundedStart, endMs = boundedEnd)
    }

    // Overlapping or out-of-order evidence cannot be assigned safely to a word.  Preserve
    // the segment text and drop the whole timing list instead of exposing stale timing.
    if (shifted.zipWithNext().any { (left, right) ->
            right.startMs < left.endMs || right.startMs < left.startMs
        }
    ) {
        return emptyList()
    }
    return shifted
}

private fun safeAdd(first: Long, second: Long): Long? {
    if (second > 0L && first > Long.MAX_VALUE - second) return null
    if (second < 0L && first < Long.MIN_VALUE - second) return null
    return first + second
}

private val WHITESPACE = Regex("\\s+")
