package com.example.one_take.editing

import com.example.one_take.captions.CaptionSegment
import com.example.one_take.engine.recordingTimeline
import com.example.one_take.engine.toEngine
import com.example.one_take.engine.toApp

/** A reversible source-video interval that may be removed from the edited view. */
internal data class EditCut(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val reason: String,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Edit cut id must not be blank" }
        require(startMs >= 0L) { "Edit cut start must be non-negative" }
        require(endMs > startMs) { "Edit cut end must be after its start" }
        require(reason.isNotBlank()) { "Edit cut reason must not be blank" }
    }
}

/** A kept interval in the original source timeline. */
internal data class SourceRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0L) { "Source range start must be non-negative" }
        require(endMs > startMs) { "Source range end must be after its start" }
    }
}

/** Millisecond adapter for the sample-based engine, retained for existing UI and sidecars. */
internal data class EditDecision(val durationMs: Long, val cuts: List<EditCut>) {
    init { toEngine() }

    fun keptRanges(): List<SourceRange> = toEngine().keptRanges().map {
        SourceRange(recordingTimeline.msFromSamples(it.startSample), recordingTimeline.msFromSamples(it.endSample))
    }

    fun toggle(id: String): EditDecision = toEngine().toggle(id).toApp()

    fun restoreAll(): EditDecision = toEngine().restoreAll().toApp()

    fun sourceToEditedTime(timeMs: Long): Long? {
        if (timeMs < 0 || timeMs > durationMs) return null
        return toEngine().sourceToEditedTime(recordingTimeline.samplesFromMillis(timeMs))
            ?.let(recordingTimeline::msFromSamples)
    }

    fun mapCaptions(segments: List<CaptionSegment>): List<CaptionSegment> =
        toEngine().mapCaptions(segments.map { it.toEngine() }).map { it.toApp() }

    companion object { const val MIN_RETAINED_DURATION_MS: Long = 250L }
}
