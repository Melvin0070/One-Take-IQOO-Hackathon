package com.onetake.engine

/**
 * The coverage state machine. Contract §6 is authoritative and this is it.
 *
 * OWNERSHIP, and this matters more than it looks: this machine lives HERE, in
 * `:engine`, as a pure fold. `:app` RENDERS `CoverageChanged` events and never
 * computes coverage. The v3 tracks table gave "coverage states" to the UI owner
 * and "aligner" to the engine owner — one machine, two owners — and with agents
 * that becomes two implementations that disagree on stage.
 *
 *   From      Event                                        To
 *   -------   ------------------------------------------   ------------------------
 *   UNREAD    a take for this line closes                  PENDING
 *   UNREAD    speech matches a later line (skipped)        NEEDED
 *   PENDING   verdict clean (must-say: word-perfect)       COVERED
 *   PENDING   verdict flubbed / missing words              NEEDED
 *   NEEDED    a new take for this line closes              PENDING
 *   COVERED   a new take of THAT line closes               COVERED (replaces the
 *                                                          cut take only if clean)
 *   COVERED   its only clean take is scratched             NEEDED
 *   NEEDED    the scratch is undone                        COVERED
 *   any       the line's text is edited                    NEEDED (id unchanged,
 *                                                          takes kept and marked
 *                                                          "read against an earlier
 *                                                          version") — R18
 *   any       the line is deleted                          takes orphaned and kept — R24
 *   any       lines are reordered                          ids unchanged; cut order
 *                                                          re-derived from positions — R24
 *   COVERED   its only clean take's video is lost          NEEDED, take shows
 *                                                          video missing — R13
 */
enum class LineState { UNREAD, PENDING, NEEDED, COVERED }

/**
 * The pure fold over the ledger. Nothing in here is cached across a session; it
 * is recomputed, which is why a crash cannot corrupt it.
 */
data class CoverageState(
    val lines: Map<LineId, LineState>,
    val takes: Map<TakeId, Take>,
    val openTake: TakeId?,
    val currentLine: LineId?,
    /**
     * Every line covered, must-say by the strict verdict.
     *
     * A PENDING verdict BLOCKS wrap ready until it lands, however long it takes.
     * The hold limit moves the strip on; it never moves the coverage state.
     * Nothing claims coverage before its verdict (§5.4 principle 5) — that is
     * the whole product.
     */
    val wrapReady: Boolean,
) {
    val coveredCount: Int get() = lines.values.count { it == LineState.COVERED }
    val neededLines: List<LineId> get() = lines.filterValues { it == LineState.NEEDED }.keys.toList()

    companion object {
        fun initial(script: Script) = CoverageState(
            lines = script.lines.associate { it.id to LineState.UNREAD },
            takes = emptyMap(),
            openTake = null,
            currentLine = script.lines.firstOrNull()?.id,
            wrapReady = false,
        )
    }
}
