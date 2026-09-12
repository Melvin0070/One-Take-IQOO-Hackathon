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
)

/** An immutable sample-based editing timeline. */
class Timeline(clips: List<Clip>) {
    val clips: List<Clip> = java.util.Collections.unmodifiableList(clips.toList())

    fun trim(clipId: String, newStart: Long, newEnd: Long): Timeline = this

    fun split(clipId: String, at: Long): Timeline = this

    fun delete(clipId: String): Timeline = this

    fun reorder(clipId: String, toIndex: Int): Timeline = this

    fun restore(clipId: String): Timeline = this

    fun keptClips(): List<Clip> = clips.filter { it.state == ClipState.KEEP }

    fun exportedDurationSamples(): Long = keptClips().sumOf { it.sourceEnd - it.sourceStart }

    override fun equals(other: Any?): Boolean = other is Timeline && clips == other.clips

    override fun hashCode(): Int = clips.hashCode()

    override fun toString(): String = "Timeline(clips=$clips)"

    companion object {
        fun fromAnalysis(duration: Long, candidates: List<RemovalCandidate>): Timeline =
            Timeline(emptyList())

        fun fromEditPlan(plan: EditPlan): Timeline = Timeline(emptyList())
    }
}
