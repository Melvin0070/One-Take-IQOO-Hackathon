package com.onetake.media

import com.onetake.engine.EditList
import com.onetake.engine.VideoAnchor

/**
 * Media3: playback from the edit list, Transformer export, caption rendering.
 * Read media/AGENTS.md and docs/agents/landmines.md L10, L11 first — several of the
 * obvious APIs here are deprecated, and one of them would break a promise made on stage.
 */

/**
 * Plays the cut. The whole product promise is that this starts the moment you stop, with
 * no render step.
 *
 * ConcatenatingMediaSource is DEPRECATED; the current idiom is the playlist API plus
 * MediaItem.ClippingConfiguration. CompositionPlayer would be natively right for the
 * fallback architecture but is @ExperimentalApi and early preview — do not bet the demo
 * on it.
 *
 * THE KEYFRAME RISK IS REAL and it is what threatens "no render bar". Media3's own guide:
 * an unaligned start position makes the player decode and discard data from the previous
 * keyframe — at EVERY segment boundary, not just the first. With CameraX's default
 * encoder the GOP length is unknown to you. In order: snap in-points to keyframes where
 * the pause allows, pre-warm the player before the Wrap tap, and MEASURE
 * stop-to-first-frame rather than assuming 300 ms.
 */
class CutPlayer {

    /**
     * Gate this on VideoRecordEvent.Finalize. Building the media source before the MP4
     * is finalized gives a black frame — a known critical gap. Show "preparing" until
     * then.
     */
    fun prepare(editList: EditList, anchor: VideoAnchor): Unit = TODO("Lane F: see media/AGENTS.md")

    /** The honest number behind the no-render claim. Log it; do not assume it. */
    fun stopToFirstFrameMillis(): Long = TODO("Lane F")
}

/**
 * Export with burned-in captions, via OverlayEffect + TextOverlay.
 *
 * NEVER experimentalSetMp4EditListTrimEnabled(true). It trims via the MP4 edit list with
 * no re-transcode — and THE TRIMMED DATA IS STILL IN THE FILE. That makes The Vanish a
 * lie and the delete-everything promise false, and the infosec juror can open the
 * exported file and check. The other speedup, experimentalSetTrimOptimizationEnabled, is
 * disqualified by burned-in captions anyway.
 *
 * Foreground-only, with visible progress. Do not promise background export in the UI.
 * Implement Service.onTimeout() even though you will never approach the 6-hour cap —
 * the missing handler is an ANR waiting to happen, and it is four lines.
 */
class CutExporter {

    fun export(editList: EditList, outputPath: String): Unit = TODO("Lane F")

    /**
     * No published numbers exist for Transformer at 1080p with overlays; Google's
     * benchmark is 720p with no overlays and is not your number. Measure cold and after
     * ten minutes of recording. If it is slow, the honest fix is to say so on the card —
     * the no-render promise was always about playback on stop, never about export.
     */
    fun measuredExportMillisPerFilmedMinute(): Long? = TODO("Lane F")
}
