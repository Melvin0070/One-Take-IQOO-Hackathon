package com.onetake.engine.timeline

import com.onetake.engine.EditPlan

/** The editing decision attached to a source interval. */
enum class ClipState {
    KEEP,
    RECOMMENDED_REMOVE,
    REMOVED,
}

/** A half-open source interval measured in audio samples. */
data class Clip(
    val id: String,
    val sourceStart: Long,
    val sourceEnd: Long,
    val state: ClipState = ClipState.KEEP,
    val reason: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Clip id must not be blank" }
        require(sourceStart >= 0L) { "Clip start must be non-negative" }
        require(sourceEnd > sourceStart) { "Clip end must be after its start" }
    }
}

/** A candidate interval that analysis suggests removing. */
data class RemovalCandidate(
    val start: Long,
    val end: Long,
    val reason: String,
) {
    init {
        require(start >= 0L) { "Removal candidate start must be non-negative" }
        require(end > start) { "Removal candidate end must be after its start" }
        require(reason.isNotBlank()) { "Removal candidate reason must not be blank" }
    }
}

/**
 * An immutable sample-based editing timeline.
 *
 * Clip intervals use a half-open range, so a clip contains samples from
 * [Clip.sourceStart] through [Clip.sourceEnd] exclusive.  The list order is
 * the current editing order and may differ from source order after reorder.
 * Distinct clips may reference overlapping source intervals to replay footage.
 * Export concatenates kept clips; it does not take the union of their ranges.
 */
class Timeline(clips: List<Clip>) {
    /** An immutable snapshot of the clips in editing order. */
    val clips: List<Clip> = immutableCopy(clips)

    init {
        val ids = HashSet<String>(this.clips.size)
        this.clips.forEach { clip ->
            require(ids.add(clip.id)) { "Duplicate clip id: ${clip.id}" }
        }
    }

    /**
     * Trims one clip while retaining its id, state, and reason.
     *
     * Both new boundaries must remain inside the clip's current source range
     * and [newStart] must be before [newEnd].  Unknown ids and invalid bounds
     * are stale editor events and return this timeline unchanged.
     */
    fun trim(clipId: String, newStart: Long, newEnd: Long): Timeline {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return this

        val clip = clips[index]
        if (newStart < clip.sourceStart || newEnd > clip.sourceEnd || newStart >= newEnd) {
            return this
        }
        if (newStart == clip.sourceStart && newEnd == clip.sourceEnd) return this

        return withReplacement(index, listOf(clip.copy(sourceStart = newStart, sourceEnd = newEnd)))
    }

    /**
     * Splits one clip at an interior sample boundary.
     *
     * Boundary and outside positions, along with unknown ids, are no-ops.
     * The two children inherit the original state and reason.  Child ids use
     * a deterministic left/right boundary format and receive a numeric
     * suffix if either generated id already exists in this timeline.
     */
    fun split(clipId: String, at: Long): Timeline {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return this

        val clip = clips[index]
        if (at <= clip.sourceStart || at >= clip.sourceEnd) return this

        val usedIds = clips.mapTo(HashSet<String>(clips.size)) { it.id }
        val leftId = uniqueId("${clip.id}:left@$at", usedIds)
        val rightId = uniqueId("${clip.id}:right@$at", usedIds)
        val children = listOf(
            clip.copy(id = leftId, sourceEnd = at),
            clip.copy(id = rightId, sourceStart = at),
        )
        return withReplacement(index, children)
    }

    /**
     * Marks a clip as removed while keeping its interval and reason for undo.
     * Unknown ids and clips already marked [ClipState.REMOVED] are no-ops.
     */
    fun delete(clipId: String): Timeline {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return this

        val clip = clips[index]
        if (clip.state == ClipState.REMOVED) return this
        return withReplacement(index, listOf(clip.copy(state = ClipState.REMOVED)))
    }

    /**
     * Moves a clip to [toIndex], interpreted as its final list index.
     *
     * Valid indices are zero through the last current index.  Unknown ids,
     * negative indices, and indices beyond the current list are no-ops.
     */
    fun reorder(clipId: String, toIndex: Int): Timeline {
        val fromIndex = clips.indexOfFirst { it.id == clipId }
        if (fromIndex < 0 || toIndex !in clips.indices || fromIndex == toIndex) return this

        val reordered = clips.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        return Timeline(reordered)
    }

    /**
     * Restores a clip to [ClipState.KEEP] while retaining its reason.
     *
     * This lets a user override either an analysis recommendation or a prior
     * deletion.  Unknown ids and clips already kept are no-ops.
     */
    fun restore(clipId: String): Timeline {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return this

        val clip = clips[index]
        if (clip.state == ClipState.KEEP) return this
        return withReplacement(index, listOf(clip.copy(state = ClipState.KEEP)))
    }

    /** Returns an immutable snapshot of clips included in the export. */
    fun keptClips(): List<Clip> = immutableCopy(clips.filter { it.state == ClipState.KEEP })

    /**
     * Returns the exact sum of [ClipState.KEEP] durations.
     *
     * [ArithmeticException] is thrown if the sum cannot be represented by a
     * signed 64-bit sample count.
     */
    fun exportedDurationSamples(): Long = keptClips().fold(0L) { total, clip ->
        Math.addExact(total, clip.sourceEnd - clip.sourceStart)
    }

    override fun equals(other: Any?): Boolean = other is Timeline && clips == other.clips

    override fun hashCode(): Int = clips.hashCode()

    override fun toString(): String = "Timeline(clips=$clips)"

    companion object {
        /**
         * Builds a timeline covering [duration] from analysis removal ranges.
         *
         * Candidates may arrive unsorted and are emitted in source order.
         * Gaps become [ClipState.KEEP] clips.  Candidates become
         * [ClipState.RECOMMENDED_REMOVE] clips with deterministic ids and
         * their supplied reasons.  Negative durations, out-of-bounds ranges,
         * and overlapping candidates are rejected.
         */
        fun fromAnalysis(duration: Long, candidates: List<RemovalCandidate>): Timeline {
            require(duration >= 0L) { "Timeline duration must be non-negative" }

            val sorted = candidates.sortedWith(
                compareBy<RemovalCandidate> { it.start }
                    .thenBy { it.end }
                    .thenBy { it.reason },
            )
            val clips = ArrayList<Clip>(sorted.size * 2 + 1)
            val usedIds = HashSet<String>(sorted.size * 2 + 1)
            var cursor = 0L

            sorted.forEach { candidate ->
                require(candidate.end <= duration) {
                    "Removal candidate must fit within the timeline duration"
                }
                require(candidate.start >= cursor) {
                    "Removal candidates must not overlap"
                }
                if (candidate.start > cursor) {
                    clips += Clip(
                        id = uniqueId("gap-$cursor-${candidate.start}", usedIds),
                        sourceStart = cursor,
                        sourceEnd = candidate.start,
                    )
                }

                clips += Clip(
                    id = uniqueId("candidate-${candidate.start}-${candidate.end}", usedIds),
                    sourceStart = candidate.start,
                    sourceEnd = candidate.end,
                    state = ClipState.RECOMMENDED_REMOVE,
                    reason = candidate.reason,
                )
                cursor = candidate.end
            }

            if (cursor < duration) {
                clips += Clip(
                    id = uniqueId("gap-$cursor-$duration", usedIds),
                    sourceStart = cursor,
                    sourceEnd = duration,
                )
            }
            return Timeline(clips)
        }

        /**
         * Maps an existing non-destructive [EditPlan] into timeline clips.
         *
         * Enabled cuts become [ClipState.RECOMMENDED_REMOVE] clips and
         * disabled cuts become [ClipState.KEEP] clips.  Cut ids and reasons
         * are preserved.  Retained gaps use generated ids that avoid every
         * original cut id, so the mapping remains uniquely addressable.
         */
        fun fromEditPlan(plan: EditPlan): Timeline {
            val clips = ArrayList<Clip>(plan.cuts.size * 2 + 1)
            val usedIds = plan.cuts.mapTo(HashSet<String>(plan.cuts.size * 2 + 1)) { it.id }
            var cursor = 0L

            plan.cuts.forEach { cut ->
                if (cut.startSample > cursor) {
                    clips += Clip(
                        id = uniqueId("gap-$cursor-${cut.startSample}", usedIds),
                        sourceStart = cursor,
                        sourceEnd = cut.startSample,
                    )
                }

                clips += Clip(
                    id = cut.id,
                    sourceStart = cut.startSample,
                    sourceEnd = cut.endSample,
                    state = if (cut.enabled) ClipState.RECOMMENDED_REMOVE else ClipState.KEEP,
                    reason = cut.reason,
                )
                cursor = cut.endSample
            }

            if (cursor < plan.durationSamples) {
                clips += Clip(
                    id = uniqueId("gap-$cursor-${plan.durationSamples}", usedIds),
                    sourceStart = cursor,
                    sourceEnd = plan.durationSamples,
                )
            }
            return Timeline(clips)
        }

        private fun uniqueId(base: String, usedIds: MutableSet<String>): String {
            var candidate = base
            var suffix = 1
            while (!usedIds.add(candidate)) {
                candidate = "$base#$suffix"
                suffix++
            }
            return candidate
        }

        private fun <T> immutableCopy(values: List<T>): List<T> =
            java.util.Collections.unmodifiableList(values.toList())
    }

    private fun withReplacement(index: Int, replacements: List<Clip>): Timeline {
        val updated = clips.toMutableList()
        updated.removeAt(index)
        updated.addAll(index, replacements)
        return Timeline(updated)
    }
}
