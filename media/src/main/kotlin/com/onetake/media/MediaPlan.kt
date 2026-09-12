package com.onetake.media

import com.onetake.engine.SAMPLE_RATE
import com.onetake.engine.Segment
import com.onetake.engine.VideoAnchor
import java.io.File

/**
 * A video file that has passed the capture finalization callback.
 *
 * The media lane receives this value only after `VideoRecordEvent.Finalize` has
 * reported success. The planner checks the file again because a path can be
 * deleted or replaced between the callback and playback/export.
 */
data class FinalizedVideo(
    val sourceKey: String,
    val file: File,
    val anchor: VideoAnchor,
) {
    init {
        require(sourceKey.isNotBlank()) { "Finalized video source key must not be blank" }
        require(file.path.isNotBlank()) { "Finalized video path must not be blank" }
        require(anchor.sample >= 0L) { "Video anchor sample must be non-negative" }
        require(anchor.videoPtsNanos >= 0L) { "Video anchor PTS must be non-negative" }
        require(anchor.sessionId.isNotBlank()) { "Video anchor session must not be blank" }
    }
}

/** Caption timing on the source sample timeline of one finalized video. */
data class CaptionCue(
    val sourceKey: String,
    val startSample: Long,
    val endSample: Long,
    val text: String,
) {
    init {
        require(sourceKey.isNotBlank()) { "Caption source key must not be blank" }
        require(startSample >= 0L) { "Caption start must be non-negative" }
        require(endSample > startSample) { "Caption end must be after its start" }
        require(text.isNotBlank()) { "Caption text must not be blank" }
    }
}

/** One clipped source interval and its position in the concatenated output. */
data class MediaClipPlan(
    val segment: Segment,
    val source: FinalizedVideo,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val editedStartMs: Long,
) {
    val editedEndMs: Long
        get() = editedStartMs + (sourceEndMs - sourceStartMs)

    init {
        require(sourceStartMs >= 0L) { "Source clip start must be non-negative" }
        require(sourceEndMs > sourceStartMs) { "Source clip end must be after its start" }
        require(editedStartMs >= 0L) { "Edited clip start must be non-negative" }
    }
}

/** Caption interval after source clips have been concatenated for playback/export. */
data class MappedCaption(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        require(startMs >= 0L) { "Mapped caption start must be non-negative" }
        require(endMs > startMs) { "Mapped caption end must be after its start" }
        require(text.isNotBlank()) { "Mapped caption text must not be blank" }
    }
}

/** Pure validation and sample-index to media-timeline conversion for both media paths. */
object MediaClipPlanner {
    fun plan(editList: com.onetake.engine.EditList, sources: Collection<FinalizedVideo>): List<MediaClipPlan> {
        val sourceByKey = indexSources(sources)
        var editedStartMs = 0L
        return editList.segments.map { segment ->
            val source = sourceByKey[segment.sourceFile]
                ?: throw IllegalArgumentException(
                    "No finalized video registered for segment source key: ${segment.sourceFile}",
                )
            check(source.file.isFile && source.file.length() > 0L) {
                "Video is not finalized or no longer available: ${source.file.path}"
            }
            require(segment.inSample >= 0L) { "Segment start must be non-negative" }
            require(segment.outSample > segment.inSample) {
                "Segment end must be after its start for ${segment.takeId.value}"
            }
            val sourceStartMs = sampleToVideoMillis(segment.inSample, source.anchor)
            val sourceEndMs = sampleToVideoMillis(segment.outSample, source.anchor)
            require(sourceStartMs >= 0L) {
                "Segment starts before the video timeline: ${segment.takeId.value}"
            }
            require(sourceEndMs > sourceStartMs) {
                "Segment is shorter than one media millisecond: ${segment.takeId.value}"
            }
            val plan = MediaClipPlan(
                segment = segment,
                source = source,
                sourceStartMs = sourceStartMs,
                sourceEndMs = sourceEndMs,
                editedStartMs = editedStartMs,
            )
            editedStartMs = plan.editedEndMs
            plan
        }
    }

    private fun indexSources(sources: Collection<FinalizedVideo>): Map<String, FinalizedVideo> {
        val index = LinkedHashMap<String, FinalizedVideo>(sources.size)
        sources.forEach { source ->
            require(index.put(source.sourceKey, source) == null) {
                "Multiple finalized videos use the same source key: ${source.sourceKey}"
            }
        }
        return index
    }

    /** Maps an engine sample index onto the MP4 timeline represented by its anchor. */
    internal fun sampleToVideoMillis(sample: Long, anchor: VideoAnchor): Long {
        val sampleDelta = Math.subtractExact(sample, anchor.sample)
        val deltaMillis = Math.floorDiv(
            Math.multiplyExact(sampleDelta, 1_000L),
            SAMPLE_RATE.toLong(),
        )
        return Math.addExact(anchor.videoPtsNanos / 1_000_000L, deltaMillis)
    }
}

/** Maps source-time captions onto the concatenated output timeline. */
object CaptionTimelineMapper {
    fun map(captions: List<CaptionCue>, plans: List<MediaClipPlan>): List<MappedCaption> {
        if (captions.isEmpty()) return emptyList()
        validateCaptions(captions)
        val plansBySource = plans.groupBy { it.source.sourceKey }
        val mapped = ArrayList<MappedCaption>(captions.size)
        captions.forEach { caption ->
            val sourcePlans = plansBySource[caption.sourceKey]
                ?: throw IllegalArgumentException(
                    "No edit-list clip uses caption source key: ${caption.sourceKey}",
                )
            sourcePlans.forEach { plan ->
                val startSample = maxOf(caption.startSample, plan.segment.inSample)
                val endSample = minOf(caption.endSample, plan.segment.outSample)
                if (startSample >= endSample) return@forEach
                val sourceStartMs = MediaClipPlanner.sampleToVideoMillis(startSample, plan.source.anchor)
                val sourceEndMs = MediaClipPlanner.sampleToVideoMillis(endSample, plan.source.anchor)
                val mappedStart = plan.editedStartMs + (sourceStartMs - plan.sourceStartMs)
                val mappedEnd = plan.editedStartMs + (sourceEndMs - plan.sourceStartMs)
                if (mappedEnd > mappedStart) {
                    mapped += MappedCaption(mappedStart, mappedEnd, caption.text)
                }
            }
        }
        return mapped.sortedWith(compareBy<MappedCaption> { it.startMs }.thenBy { it.endMs })
    }

    private fun validateCaptions(captions: List<CaptionCue>) {
        captions.groupBy { it.sourceKey }.forEach { (sourceKey, sourceCaptions) ->
            var previousEnd = 0L
            sourceCaptions.sortedBy { it.startSample }.forEachIndexed { index, caption ->
                require(index == 0 || caption.startSample >= previousEnd) {
                    "Captions must be sorted and non-overlapping for source key $sourceKey"
                }
                previousEnd = caption.endSample
            }
        }
    }
}
