package com.onetake.engine

@JvmInline value class LineId(val value: String)
@JvmInline value class TakeId(val value: String)

enum class LineType {
    /** The default. Judged against the clean threshold. */
    LINE,

    /**
     * A disclosure, a qualification, a learning objective. Word-perfect: every
     * word, in order. This is the mechanism behind brief mode (§8), and it is
     * also the likeliest source of a false flag on a clean read — which Premise
     * 10 ranks as the worst failure there is. Two rules follow, and neither is
     * optional:
     *   - a must-say line is NEVER split by the one-breath rule (Contract §7.3)
     *   - a must-say take is NEVER spliced for fillers (Contract §7.4)
     */
    MUST_SAY,

    /** Tier 2, talking-points mode. Matched by meaning, not by words. */
    TALKING_POINT,

    /** Tier 3. */
    CUTAWAY,
}

data class ScriptLine(
    /** Stable across text edits (R18) and across sessions, so pickups join the same cut. */
    val id: LineId,
    val text: String,
    val type: LineType = LineType.LINE,
    /** Bumps on text edit. Takes record the version they were read against. */
    val version: Int = 1,
)

data class Script(val lines: List<ScriptLine>) {
    fun line(id: LineId): ScriptLine? = lines.firstOrNull { it.id == id }
    fun indexOf(id: LineId): Int = lines.indexOfFirst { it.id == id }
}
