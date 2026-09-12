package com.example.one_take.editing

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import com.example.one_take.audio.SpeechPauseDetector
import com.onetake.engine.VoiceActivitySource

/** Audio identifies pauses; the actual video duration remains the edit timeline boundary. */
internal fun analyzePauses(source: File, audio: FloatArray, context: Context): EditDecision =
    analyzePauseActivity(source, audio, context).decision

internal data class PauseAnalysis(val decision: EditDecision, val source: VoiceActivitySource)

internal fun analyzePauseActivity(source: File, audio: FloatArray, context: Context): PauseAnalysis {
    // This classifier owns fresh state on the saved MEDIA timeline, independent of the mic.
    val (candidates, activitySource) = SpeechPauseDetector.create(context).use { it.append(audio) to it.source }
    val detected = EditDecision(
        (audio.size.toLong() * 1_000L + 15_999L) / 16_000L,
        candidates.map { candidate ->
            EditCut(
                id = "silence-${candidate.startSample / 16}-${candidate.endSample / 16}",
                startMs = candidate.startSample / 16,
                endMs = candidate.endSample / 16,
                reason = "silence",
            )
        },
    )
    val metadata = MediaMetadataRetriever()
    val duration = try {
        metadata.setDataSource(source.absolutePath)
        metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?.takeIf { it > 0 } ?: detected.durationMs
    } finally {
        metadata.release()
    }
    return PauseAnalysis(EditDecision(duration, detected.cuts.mapNotNull { cut ->
        val end = cut.endMs.coerceAtMost(duration)
        if (end > cut.startMs) cut.copy(endMs = end) else null
    }), activitySource)
}
