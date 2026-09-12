package com.onetake.engine

import java.util.Locale

/** A timed word returned by the recognizer for a caption. */
data class TranscriptWord(
    val startSample: Long,
    val endSample: Long,
    val text: String,
    val confidence: Float,
) {
    init {
        require(startSample >= 0L) { "Transcript word start must be non-negative" }
        require(endSample > startSample) { "Transcript word end must be after its start" }
        require(text.isNotBlank()) { "Transcript word text must not be blank" }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Transcript word confidence must be finite and between 0 and 1"
        }
    }
}

/** A caption interval on the source sample timeline. */
data class Caption(
    val startSample: Long,
    val endSample: Long,
    val text: String,
    val words: List<TranscriptWord> = emptyList(),
) {
    init {
        require(startSample >= 0L) { "Caption start must be non-negative" }
        require(endSample > startSample) { "Caption end must be after its start" }
        require(text.isNotBlank()) { "Caption text must not be blank" }

        var previousEnd = startSample
        words.forEachIndexed { index, word ->
            require(word.startSample >= startSample && word.endSample <= endSample) {
                "Transcript word must be within its caption (index $index)"
            }
            require(word.startSample >= previousEnd) {
                "Transcript words must be sorted and non-overlapping (index $index)"
            }
            previousEnd = word.endSample
        }
    }
}

/** A reversible source interval that can be removed from the edited result. */
data class Cut(
    val id: String,
    val startSample: Long,
    val endSample: Long,
    val reason: String,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Cut id must not be blank" }
        require(startSample >= 0L) { "Cut start must be non-negative" }
        require(endSample > startSample) { "Cut end must be after its start" }
        require(reason.isNotBlank()) { "Cut reason must not be blank" }
    }
}

/** A source interval retained by an [EditPlan]. */
data class SampleRange(
    val startSample: Long,
    val endSample: Long,
) {
    init {
        require(startSample >= 0L) { "Range start must be non-negative" }
        require(endSample > startSample) { "Range end must be after its start" }
    }
}

/**
 * Non-destructive editing decisions for one source recording.
 *
 * Cuts are kept in source order.  The original source is never modified, and
 * disabling a cut preserves its decision for review and undo.
 */
class EditPlan(
    val durationSamples: Long,
    cuts: List<Cut>,
) {
    /** An immutable snapshot of the caller's cut list. */
    val cuts: List<Cut> = immutableCopy(cuts)

    init {
        require(durationSamples >= 0L) { "Recording duration must be non-negative" }

        val ids = HashSet<String>(this.cuts.size)
        var previousEnd = 0L
        this.cuts.forEachIndexed { index, cut ->
            require(ids.add(cut.id)) { "Duplicate cut id: ${cut.id}" }
            require(cut.startSample >= previousEnd) {
                "Cuts must be sorted and non-overlapping (index $index)"
            }
            require(cut.endSample <= durationSamples) {
                "Cut must be within the recording duration (index $index)"
            }
            previousEnd = cut.endSample
        }

        var retained = durationSamples
        this.cuts.asSequence()
            .filter { it.enabled }
            .forEach { cut ->
                retained = Math.subtractExact(retained, cut.endSample - cut.startSample)
            }
        val minimumRetained = minOf(MIN_RETAINED_SAMPLES, durationSamples)
        require(retained >= minimumRetained) {
            "Edit plan must retain at least $minimumRetained samples"
        }
    }

    /** Returns source intervals left after applying enabled cuts. */
    fun keptRanges(): List<SampleRange> {
        if (durationSamples == 0L) return emptyList()

        val ranges = ArrayList<SampleRange>()
        var cursor = 0L
        cuts.asSequence()
            .filter { it.enabled }
            .forEach { cut ->
                if (cut.startSample > cursor) {
                    ranges += SampleRange(cursor, cut.startSample)
                }
                cursor = maxOf(cursor, cut.endSample)
            }
        if (cursor < durationSamples) {
            ranges += SampleRange(cursor, durationSamples)
        }
        return immutableCopy(ranges)
    }

    /**
     * Maps a source sample position into the edited timeline.
     * A position inside an enabled cut has no edited equivalent.
     */
    fun sourceToEditedTime(sample: Long): Long? {
        if (sample < 0L || sample > durationSamples) return null
        if (cuts.any { it.enabled && sample >= it.startSample && sample < it.endSample }) {
            return null
        }
        return editedBoundaryTime(sample)
    }

    /** Applies enabled cuts to captions, splitting captions at retained ranges. */
    fun mapCaptions(captions: List<Caption>): List<Caption> {
        val sourceCaptions = immutableCopy(captions.map(::copyCaption))
        validateCaptions(sourceCaptions)
        if (sourceCaptions.isEmpty() || cuts.none { it.enabled }) {
            return sourceCaptions
        }

        val ranges = keptRanges()
        val mapped = ArrayList<Caption>(sourceCaptions.size)
        sourceCaptions.forEach { caption ->
            ranges.forEach rangeLoop@{ range ->
                val start = maxOf(caption.startSample, range.startSample)
                val end = minOf(caption.endSample, range.endSample)
                if (start >= end) return@rangeLoop

                val editedStart = editedBoundaryTime(start)
                val editedEnd = editedBoundaryTime(end)
                if (editedEnd > editedStart) {
                    if (hasCompleteWordEvidence(caption)) {
                        val retainedWords = caption.words.filter { word ->
                            word.startSample >= start && word.endSample <= end
                        }
                        if (retainedWords.isEmpty()) return@rangeLoop

                        val mappedWords = retainedWords.map { word ->
                            TranscriptWord(
                                startSample = editedBoundaryTime(word.startSample),
                                endSample = editedBoundaryTime(word.endSample),
                                text = word.text,
                                confidence = word.confidence,
                            )
                        }
                        mapped += Caption(
                            startSample = editedStart,
                            endSample = editedEnd,
                            text = mappedWords.joinToString(" ") { it.text },
                            words = mappedWords,
                        )
                    } else {
                        // A partial word list cannot safely describe the text
                        // after a cut. Preserve the legacy caption text while
                        // dropping stale word evidence.
                        mapped += Caption(editedStart, editedEnd, caption.text)
                    }
                }
            }
        }
        return immutableCopy(mapped)
    }

    /** Toggles a cut.  Unknown ids are treated as stale UI events and are a no-op. */
    fun toggle(id: String): EditPlan {
        if (cuts.none { it.id == id }) return this
        return EditPlan(
            durationSamples = durationSamples,
            cuts = cuts.map { cut ->
                if (cut.id == id) cut.copy(enabled = !cut.enabled) else cut
            },
        )
    }

    /** Disables every cut while retaining the decisions for later review. */
    fun restoreAll(): EditPlan = EditPlan(
        durationSamples = durationSamples,
        cuts = cuts.map { it.copy(enabled = false) },
    )

    override fun equals(other: Any?): Boolean =
        other is EditPlan && durationSamples == other.durationSamples && cuts == other.cuts

    override fun hashCode(): Int = 31 * durationSamples.hashCode() + cuts.hashCode()

    override fun toString(): String =
        "EditPlan(durationSamples=$durationSamples, cuts=$cuts)"

    private fun editedBoundaryTime(sample: Long): Long {
        var edited = 0L
        for (range in keptRanges()) {
            if (sample < range.startSample) return edited
            if (sample < range.endSample) {
                return Math.addExact(edited, sample - range.startSample)
            }
            edited = Math.addExact(edited, range.endSample - range.startSample)
        }
        return edited
    }

    private fun validateCaptions(captions: List<Caption>) {
        var previousEnd = 0L
        captions.forEachIndexed { index, caption ->
            require(caption.startSample >= previousEnd) {
                "Captions must be sorted and non-overlapping (index $index)"
            }
            require(caption.endSample <= durationSamples) {
                "Caption must be within the recording duration (index $index)"
            }
            previousEnd = caption.endSample
        }
    }

    private fun hasCompleteWordEvidence(caption: Caption): Boolean {
        if (caption.words.isEmpty()) return false
        return lexicalTokens(caption.text) == caption.words.map { lexicalToken(it.text) }
    }

    private fun lexicalTokens(text: String): List<String> = text
        .trim()
        .split(Regex("\\s+"))
        .map(::lexicalToken)
        .filter(String::isNotEmpty)

    private fun lexicalToken(text: String): String = text
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun copyCaption(caption: Caption): Caption = Caption(
        startSample = caption.startSample,
        endSample = caption.endSample,
        text = caption.text,
        words = immutableCopy(caption.words),
    )

    companion object {
        /** A cut may not remove the entire useful recording. */
        const val MIN_RETAINED_SAMPLES: Long = 4_000L

        private fun <T> immutableCopy(values: List<T>): List<T> =
            java.util.Collections.unmodifiableList(values.toList())
    }
}
