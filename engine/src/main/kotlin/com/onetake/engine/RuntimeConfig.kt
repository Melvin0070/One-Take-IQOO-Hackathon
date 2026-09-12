package com.onetake.engine

/**
 * Everything tunable, in one place, versioned.
 *
 * Red Light exists to tune these, and Red Light has no rebuilds. New versions
 * arrive over Office Kit file transfer and are imported into app storage like
 * models, SHA-256 verified (R11). Nothing here may be hardcoded at a call site.
 *
 * Every Tier 1+ feature sits behind a flag in [flags], OFF by default. A flag
 * turns on when the feature's own check passes AND the Tier 0 check still
 * passes. Anything unverified stays off. (§10 gate mechanics.)
 */
data class RuntimeConfig(
    /** R25 + §7.3: the eval card names the build AND the config it describes. */
    val configVersion: String,

    // --- thresholds, per line type ---
    /** Clean if line-length-aware similarity is at or above this. Leans to PRECISION. */
    val cleanThreshold: Float = 0.85f,
    /** Must-say: every word, in order. Strictly above the normal bar. */
    val mustSayThreshold: Float = 0.98f,
    val captionThreshold: Float = 0.60f,

    // --- timing, in milliseconds at the config edge, samples inside ---
    /**
     * Silence that closes a take.
     *
     * DO NOT START THIS AT THE MEDIAN BETWEEN-LINE PAUSE — half of line ends
     * would then fail to close on silence and "again, line N" arrives late.
     * P3 measures WITHIN-line and BETWEEN-line pause distributions SEPARATELY;
     * this number comes from the gap between them, not from one median.
     */
    val lineEndPauseMillis: Long = 700,
    /**
     * How long the strip holds on the current line waiting for a verdict.
     * Past the limit the strip advances — but coverage does NOT. A late flubbed
     * verdict marks the line needed and prompts at the next line-end pause.
     */
    val holdLimitMillis: Long = 1_500,

    // --- aligner ---
    /**
     * How much better a non-adjacent line must match before an utterance is
     * charged to it instead of the current / next / needed lines.
     * R20 is the acceptance test: a re-read of a covered line joins THAT line.
     */
    val lineMatchMarginBias: Float = 0.10f,
    /** R2: brand names and product names the recognizer will mangle. */
    val brandAliases: Map<String, List<String>> = emptyMap(),
    /**
     * R8: an utterance consisting ONLY of one of these scratches. A script line
     * that CONTAINS "cut" must never scratch — the guard is "standalone
     * utterance", not "contains".
     */
    val commandPhrases: List<String> = listOf("scratch that", "cut"),
    /** Contract §7.5: splice only where there is silence on BOTH sides. */
    val fillerSpliceGuardMillis: Long = 150,

    // --- capture ---
    val deviceOffsetSamples: Map<String, Long> = emptyMap(),
    val preferNpuRecognizer: Boolean = false,

    val flags: FeatureFlags = FeatureFlags(),
) {
    /**
     * R25. Both frozen demo loaners must show the SAME two values — this and
     * [configVersion] — on the engine-stats overlay before the demo. Ten seconds
     * of checking, and it prevents the worst avoidable failure of the weekend:
     * two phones running the same APK with different flags.
     *
     * Deliberately a plain stable hash: reproducible across processes, no
     * dependency, readable off a screen at 1 m.
     */
    fun flagVectorChecksum(): String {
        val bits = flags.asOrderedList().joinToString("") { if (it.second) "1" else "0" }
        var h = 0x811C9DC5.toInt()
        for (c in (bits + "|" + configVersion)) { h = (h xor c.code) * 0x01000193 }
        return "%08X".format(h)
    }
}

/**
 * Tier 1+ features. Order is FROZEN — [RuntimeConfig.flagVectorChecksum] reads
 * it positionally, so inserting a flag in the middle changes every checksum.
 * Append only.
 */
data class FeatureFlags(
    val whyFlagged: Boolean = false,                 // #12
    val scratchThatVoice: Boolean = false,           // #13
    val mustSayLines: Boolean = false,               // #14
    val fillerMarks: Boolean = false,                // #15
    /**
     * Splicing fillers out of a CLEAN take. OFF on the demo build, deliberately:
     * an audible splice inside the juror's own clean read, in the payoff beat,
     * is Premise 10's worst failure wearing a different hat. Flubbed-take
     * splicing is the shipped behaviour and does not need this flag.
     */
    val fillerSplicingCleanTakes: Boolean = false,   // #15, demo-off by decision
    val freeTalkMode: Boolean = false,               // #16
    val faceReframe: Boolean = false,                // #17
    val circleUndo: Boolean = false,                 // #18
    val setupCoachChips: Boolean = false,            // #19
    val talkingPoints: Boolean = false,              // #20
    val multicam: Boolean = false,                   // #21
    val captionAccuracyPass: Boolean = false,        // #22
    /** #28. Ships, but OFF on the demo phone so "the card describes this build" stays true. */
    val personalCalibration: Boolean = false,
    val scriptCheck: Boolean = false,                // #35
    val briefModeReport: Boolean = false,            // #36
) {
    fun asOrderedList(): List<Pair<String, Boolean>> = listOf(
        "whyFlagged" to whyFlagged,
        "scratchThatVoice" to scratchThatVoice,
        "mustSayLines" to mustSayLines,
        "fillerMarks" to fillerMarks,
        "fillerSplicingCleanTakes" to fillerSplicingCleanTakes,
        "freeTalkMode" to freeTalkMode,
        "faceReframe" to faceReframe,
        "circleUndo" to circleUndo,
        "setupCoachChips" to setupCoachChips,
        "talkingPoints" to talkingPoints,
        "multicam" to multicam,
        "captionAccuracyPass" to captionAccuracyPass,
        "personalCalibration" to personalCalibration,
        "scriptCheck" to scriptCheck,
        "briefModeReport" to briefModeReport,
    )
}
