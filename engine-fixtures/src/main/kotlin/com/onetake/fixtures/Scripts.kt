package com.onetake.fixtures

import com.onetake.engine.*

/**
 * Terse builders, so a test reads as the scenario it describes rather than as twenty
 * lines of construction. Ids are derived from position and are stable across runs —
 * a test that depends on a random id is a test that cannot be a golden ledger.
 */
object Scripts {

    fun of(vararg texts: String): Script =
        Script(texts.mapIndexed { i, t -> ScriptLine(LineId("L${i + 1}"), t) })

    /** The Tier 0 check's script: three lines, one deliberate flub each session. */
    fun threeLine(): Script = of(
        "Welcome back to the channel.",
        "Today we are testing the iQOO 15 with Snapdragon 8 Elite Gen 5.",
        "Let me know what you think in the comments.",
    )

    /**
     * R2's acceptance test, which is also the normalizer's whole specification:
     * strangers read these cleanly and must get ZERO flags.
     */
    fun hardToNormalize(): Script = of(
        "The iQOO 15 costs around ₹70k.",
        "It runs the Snapdragon 8 Elite Gen 5.",
        "You get 4K at 60 fps, and this is 2026.",
    )

    /** Line 2 is a must-say disclosure. Never split, never spliced, word-perfect. */
    fun withMustSay(): Script = Script(
        listOf(
            ScriptLine(LineId("L1"), "Here is what I think of this phone."),
            ScriptLine(LineId("L2"), "This video is a paid partnership.", LineType.MUST_SAY),
            ScriptLine(LineId("L3"), "Let me know what you think in the comments."),
        )
    )
}

/** A recorded session: the inputs, in submit order. Feed with `inputs.forEach(engine::submit)`. */
data class RecordedSession(
    val sessionId: String,
    val script: Script,
    val config: RuntimeConfig,
    val inputs: List<EngineInput>,
)
