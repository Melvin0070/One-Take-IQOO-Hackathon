package com.onetake.engine

/**
 * The append-only ledger. Contract §5.
 *
 * This is the ONLY output of the engine, and derived state is always recomputed
 * from it. A crash never corrupts it, because there is nothing to corrupt: you
 * replay the log.
 *
 * It is also the reason most gate checks can be automated. A feature check like
 * "every flub prompts at its line-end pause, no clean line prompts, Safe to wrap
 * appears only at full coverage" is a set of assertions over these events — and
 * they run in a pure-JVM test in two seconds, not in five human minutes at a
 * gate window you only have 60 of. (Playbook §0, §4.)
 *
 * Every event carries [sessionId] and [sample]. [id] is monotonic within a
 * project and is what `UserAction.Undo` refers to.
 */
sealed interface LedgerEvent {
    val id: Long
    val sessionId: String
    val sample: Long

    data class UtteranceClosed(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val startSample: Long, val text: String,
    ) : LedgerEvent

    data class TakeOpened(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId, val lineId: LineId, val lineVersion: Int,
        val coveredLineIds: List<LineId> = listOf(lineId),
    ) : LedgerEvent

    data class TakeClosed(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId, val reason: CloseReason,
    ) : LedgerEvent

    data class VerdictReached(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId, val verdict: Verdict,
        /** R22: three components, never one number. R6 cannot be tuned from a total. */
        val latency: VerdictLatency,
    ) : LedgerEvent

    data class CoverageChanged(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val lineId: LineId, val state: LineState,
    ) : LedgerEvent

    data class RetakeNeeded(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val lineId: LineId,
    ) : LedgerEvent

    data class TakeScratched(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId, val source: ScratchSource,
    ) : LedgerEvent

    /** A reversible user action. The target event remains in the log for auditability. */
    data class UndoApplied(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val undoneEventId: Long,
    ) : LedgerEvent

    data class TakeCircled(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId,
    ) : LedgerEvent

    data class TakeOffFrame(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId,
    ) : LedgerEvent

    data class TakeVideoMissing(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val takeId: TakeId,
    ) : LedgerEvent

    data class LineEdited(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val lineId: LineId, val version: Int, val text: String,
    ) : LedgerEvent

    data class LineDeleted(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val lineId: LineId,
    ) : LedgerEvent

    data class LineReordered(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val order: List<LineId>,
    ) : LedgerEvent

    data class WrapReady(
        override val id: Long, override val sessionId: String, override val sample: Long,
    ) : LedgerEvent

    data class SessionStopped(
        override val id: Long, override val sessionId: String, override val sample: Long,
    ) : LedgerEvent

    data class PlaybackReady(
        override val id: Long, override val sessionId: String, override val sample: Long,
        /** The honest "no render bar" number. Measured, never assumed. */
        val stopToFirstFrameMillis: Long,
    ) : LedgerEvent

    data class ExportProgress(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val percent: Int,
    ) : LedgerEvent

    data class ProjectDeleted(
        override val id: Long, override val sessionId: String, override val sample: Long,
    ) : LedgerEvent

    data class SyncOffset(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val device: String, val offsetSamples: Long,
    ) : LedgerEvent

    /** R23. The remote connecting moves the mic; record it or the cliff is unexplainable. */
    data class AudioRouteChanged(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val device: String,
    ) : LedgerEvent

    /** R9's honesty clause: if the NPU count is zero, this names why. */
    data class ThermalChanged(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val status: String,
    ) : LedgerEvent

    /** R9: model, processor, microseconds — per session, on the record. */
    data class VisionInference(
        override val id: Long, override val sessionId: String, override val sample: Long,
        val processor: String, val micros: Long, val faceInFrame: Boolean,
    ) : LedgerEvent
}

enum class CloseReason { LINE_END_PAUSE, NEXT_LINE_MATCHED, ADVANCE, STOP, MUST_SAY_BOUNDARY }

enum class ScratchSource { VOICE, TAP, REMOTE, AUTO }

/**
 * R22. Three numbers, logged separately, reported separately on the eval card.
 * A single "verdict latency" cannot be tuned, and the Red blocks exist to tune it.
 */
data class VerdictLatency(
    val vadEndpointMicros: Long,
    val decodeFinalizeMicros: Long,
    val alignMicros: Long,
) {
    val totalMicros: Long get() = vadEndpointMicros + decodeFinalizeMicros + alignMicros
}
