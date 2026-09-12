package com.onetake.engine

import java.util.Collections

/** Live-event boundary: every live signal writes into one session through this. */
fun interface RecordingSession {
    fun record(sample: Long, change: Change, clock: ClockDomain)

    /** Convenience for [Change.SignalObserved]; live signals use their own clock domain. */
    fun signal(signal: SessionSignal) = record(signal.endSample, Change.SignalObserved(signal), signal.liveClock)
}

enum class SessionMode { SCRIPT, ASSISTED }

/** Durable header of a recording session, carried by its first [Change.CaptureRequested] event. */
data class SessionHeader(
    val sourceName: String,
    val mode: SessionMode,
    val script: String?,
    val startedAtEpochMs: Long?,
)

/**
 * A timestamped observation produced while (or after) recording.
 *
 * Spans are in 16 kHz samples of the event's clock. Live observations use [liveClock]; a copy
 * confirmed against the finalized recording is recorded in [ClockDomain.MEDIA]. The two are never
 * merged, so a provisional recognizer timestamp can never masquerade as media time.
 *
 * Adding a kind needs a codec entry only: the reducer and [RecordingSessionReader] handle every
 * signal generically.
 */
sealed interface SessionSignal {
    /** Unique per kind within a session; producers derive it deterministically so replays dedupe. */
    val id: String
    val startSample: Long
    val endSample: Long
    val liveClock: ClockDomain
}

/** A committed live transcript segment. Provisional text that may still change is not a segment. */
data class TranscriptSegment(
    override val id: String,
    override val startSample: Long,
    override val endSample: Long,
    val text: String,
    val words: List<TranscriptWord> = emptyList(),
) : SessionSignal {
    override val liveClock get() = ClockDomain.RECOGNIZER

    init {
        requireSpan(id, startSample, endSample)
        require(text.isNotBlank()) { "Transcript segment text must not be blank" }
        words.forEach { word ->
            require(word.startSample >= startSample && word.endSample <= endSample) {
                "Transcript segment words must be within the segment"
            }
        }
    }
}

enum class VoiceActivitySource { SILERO, WEBRTC, NEAR_SILENCE }

/** An interior pause detected by voice activity, not by amplitude alone. */
data class Silence(
    override val id: String,
    override val startSample: Long,
    override val endSample: Long,
    val source: VoiceActivitySource,
) : SessionSignal {
    override val liveClock get() = ClockDomain.RECOGNIZER

    init {
        requireSpan(id, startSample, endSample)
        require(endSample > startSample) { "Silence must have a positive duration" }
    }
}

/** A filler word or sound, with word-level timing. [text] is the matched lexicon entry. */
data class Filler(
    override val id: String,
    override val startSample: Long,
    override val endSample: Long,
    val text: String,
) : SessionSignal {
    override val liveClock get() = ClockDomain.RECOGNIZER

    init {
        requireSpan(id, startSample, endSample)
        require(text.isNotBlank()) { "Filler text must not be blank" }
    }
}

/**
 * An instantaneous face/gaze reading. [onCamera] is null when no gaze estimate exists, which is
 * different from looking away.
 */
data class GazeSample(
    override val id: String,
    val sample: Long,
    val faceInFrame: Boolean,
    val onCamera: Boolean? = null,
) : SessionSignal {
    override val startSample get() = sample
    override val endSample get() = sample
    override val liveClock get() = ClockDomain.CAPTURE_ESTIMATE

    init {
        requireSpan(id, sample, sample)
        require(faceInFrame || onCamera != true) { "Gaze cannot be on camera without a face" }
    }
}

/**
 * One attempt at a piece of content. Attempts sharing [groupId] are the same content spoken more
 * than once; [attemptIndex] starts at 1. Grouping never removes anything.
 */
data class TakeAttempt(
    override val id: String,
    val groupId: String,
    val attemptIndex: Int,
    override val startSample: Long,
    override val endSample: Long,
) : SessionSignal {
    override val liveClock get() = ClockDomain.RECOGNIZER

    init {
        requireSpan(id, startSample, endSample)
        require(groupId.isNotBlank()) { "Take group id must not be blank" }
        require(attemptIndex >= 1) { "Take attempt index starts at 1" }
    }
}

private fun requireSpan(id: String, start: Long, end: Long) {
    require(id.isNotBlank()) { "Signal id must not be blank" }
    require(start >= 0L) { "Signal start must be non-negative" }
    require(end >= start) { "Signal end must not precede its start" }
}

/** Stable identity used to reject a signal recorded twice in the same clock domain. */
fun SessionSignal.key(clock: ClockDomain): String = "${this::class.simpleName}:${clock.name}:$id"

/** A signal together with the clock and journal position it was recorded at. */
data class ObservedSignal(
    val sequence: Long,
    val clock: ClockDomain,
    val signal: SessionSignal,
)

/** A recording session reconstructed from its journal. */
data class RecordingSessionSnapshot(
    val sessionId: String,
    val header: SessionHeader,
    val phase: SessionPhase,
    /** Fingerprint of the finalized source, once finalized. */
    val sourceId: String?,
    val durationSamples: Long?,
    val scriptProgress: ScriptProgress?,
    val signals: List<ObservedSignal>,
) {
    inline fun <reified T : SessionSignal> signals(clock: ClockDomain? = null): List<T> =
        signals.filter { clock == null || it.clock == clock }.map { it.signal }.filterIsInstance<T>()
}

object RecordingSessionReader {
    /**
     * Folds a validated event history. Events this reader does not know are skipped, so a newer
     * event type never breaks an older reader.
     */
    fun read(history: List<Event>): RecordingSessionSnapshot {
        val first = history.firstOrNull() ?: throw IllegalArgumentException("Session history is empty")
        val request = first.change as? Change.CaptureRequested
            ?: throw IllegalArgumentException("Session history must begin with a capture request")
        val state = EditingEngine(first.sessionId, history = history).snapshot()
        val signals = history.mapNotNull { event ->
            (event.change as? Change.SignalObserved)?.let { ObservedSignal(event.sequence, event.clock, it.signal) }
        }
        return RecordingSessionSnapshot(
            sessionId = first.sessionId,
            header = SessionHeader(request.sourceName, request.mode, request.script, request.startedAtEpochMs),
            phase = state.phase,
            sourceId = state.sourceId,
            durationSamples = state.durationSamples.takeIf { state.sourceId != null },
            scriptProgress = state.scriptProgress,
            signals = Collections.unmodifiableList(signals),
        )
    }
}

/** Receives each committed live transcript segment exactly once, in order. */
fun interface TranscriptListener {
    fun onSegment(segment: TranscriptSegment)
}
