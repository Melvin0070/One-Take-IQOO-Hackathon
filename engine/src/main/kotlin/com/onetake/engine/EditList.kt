package com.onetake.engine

/**
 * The cut. Always derived from the ledger, never destructive.
 *
 * SELECTION AND ORDERING ARE TWO SEPARATE RULES. Conflating them is R21, and the
 * natural misreading scrambles the cut on stage:
 *
 *   Selection — per line: the circled take if there is one, else the LATEST
 *               CLEAN take of that line across ALL of the project's sessions,
 *               by take-close time.
 *   Ordering  — the cut is ordered by SCRIPT LINE ORDER. Never by take-close
 *               time. Cover line 3, then record a pickup for line 1, and the cut
 *               plays line 1 then line 3.
 *
 * A take marked off-frame is never flagged and never marks its line needed, but
 * it LOSES to any other clean take of that line here, and the review screen says
 * why (R9). That is the vision model's real job in the edit — the reason the NPU
 * is not a prop.
 */
data class EditList(val segments: List<Segment>)

data class Segment(
    val takeId: TakeId,
    val lineId: LineId,
    val sourceFile: String,
    /** Which phone it came from. Single-phone until `:link` ships. */
    val deviceId: String,
    /**
     * In/out points as sample indices. These land in VAD silences — real
     * silences — which is the only place it is safe to cut audio.
     *
     * Playback maps them onto the MP4 through the session's one [VideoAnchor].
     * Snap in-points to keyframes where the pause allows: Media3 decodes and
     * discards from the previous keyframe otherwise, at EVERY segment boundary,
     * and that is the thing that threatens "no render bar". (Contract §12.)
     */
    val inSample: Long,
    val outSample: Long,
    val role: SegmentRole = SegmentRole.PRIMARY,
    val captionSource: CaptionSource = CaptionSource.SCRIPT,
    val fadeInMillis: Int = 0,
    val fadeOutMillis: Int = 0,
)

enum class SegmentRole { PRIMARY, COVER, CUTAWAY }

/** Script mode captions come from the script. Free-talk captions come from recognition. */
enum class CaptionSource { SCRIPT, RECOGNITION }
