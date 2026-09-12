package com.onetake.engine

/**
 * One attempt at one line. A take can span several utterances.
 *
 * Nothing here is ever deleted. Scratching, editing a line, deleting a line and
 * losing video all set a field — Premise 12, non-destructive editing, always.
 */
data class Take(
    val id: TakeId,
    val lineId: LineId,
    val sessionId: String,
    val startSample: Long,
    val endSample: Long,
    val verdict: Verdict?,
    val scratched: Boolean = false,
    val circled: Boolean = false,
    /** The NPU vision signal saw the face leave frame. A NOTE, never a flag. */
    val offFrame: Boolean = false,
    /** Verdict survived a crash, video did not (R13). Cannot cover a line. */
    val videoMissing: Boolean = false,
    /** Its line was deleted (R24). Kept, shown as orphaned, not in the cut. */
    val orphaned: Boolean = false,
    val lineVersion: Int = 1,
) {
    /** The only definition of "this take can cover its line". Do not re-derive it. */
    val usable: Boolean
        get() = !scratched && !videoMissing && !orphaned && verdict is Verdict.Clean
}

sealed interface Verdict {
    data class Clean(val accuracy: Float) : Verdict
    data class Flubbed(val reason: MiscueType, val detail: String) : Verdict
    data class MissingWords(val missing: List<String>) : Verdict
}

/**
 * DRY: this enum is the single source for §5.1's plain-word reasons, §7.3's
 * Reading-Progress miscue types, R4's per-verdict reason and #12's display.
 * One enum in `:engine`, one presentation map in `:app`. Not four lists.
 *
 * The UI never shows these names. It shows words a creator uses:
 * "missed 'every Sunday'", "restarted", "said 'seventy' for 'seven'",
 * "extra words". Never "similarity", "confidence" or "utterance".
 */
enum class MiscueType {
    OMISSION,
    INSERTION,
    MISPRONUNCIATION,
    REPETITION,
    SELF_CORRECTION,
}
