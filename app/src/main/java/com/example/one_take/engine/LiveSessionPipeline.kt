package com.example.one_take.engine

import com.example.one_take.RecordingMode
import com.example.one_take.ScriptCaptureController
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.vision.FaceObservation
import com.onetake.engine.*

/** Where one take's live observations are journaled; [LiveCaptureCoordinator] in the app. */
internal interface LiveSessionSink {
    fun signal(session: String, signal: SessionSignal, clock: ClockDomain)
    fun scriptEvent(session: String, sample: Long, change: Change, clock: ClockDomain)

    companion object {
        fun of(coordinator: LiveCaptureCoordinator) = object : LiveSessionSink {
            override fun signal(session: String, signal: SessionSignal, clock: ClockDomain) =
                coordinator.signal(session, signal, clock)
            override fun scriptEvent(session: String, sample: Long, change: Change, clock: ClockDomain) =
                coordinator.scriptEvent(session, sample, change, clock)
        }
    }
}

/** Take grouping input; Script Mode also reports the matcher's progress after each segment. */
internal interface LiveTakeListener : TranscriptListener {
    fun onScriptProgress(segment: TranscriptSegment, progress: ScriptProgress)
}

internal fun LiveTakeDetector.asListener() = object : LiveTakeListener {
    override fun onSegment(segment: TranscriptSegment) = this@asListener.onSegment(segment)
    override fun onScriptProgress(segment: TranscriptSegment, progress: ScriptProgress) =
        this@asListener.onScriptProgress(segment, progress)
}

internal fun RecordingMode.toSession() = when (this) {
    RecordingMode.Script -> SessionMode.SCRIPT
    RecordingMode.Assisted -> SessionMode.ASSISTED
}

/**
 * Routes one take's live transcript and face observations into its recording session and the
 * live detectors. Main-thread confined: caption updates, camera callbacks and teleprompter taps
 * all arrive there, which keeps delivery order equal to journal order.
 */
internal class LiveSessionPipeline(
    val mode: SessionMode,
    script: String?,
    private val sink: LiveSessionSink,
    fillers: (RecordingSession) -> TranscriptListener = ::LiveFillerTracker,
    takes: (RecordingSession, SessionMode) -> LiveTakeListener = { session, mode -> LiveTakeDetector(session, mode).asListener() },
) {
    /** The header script; only Script Mode sessions carry one. */
    val script: String? = script?.trim()?.takeIf { mode == SessionMode.SCRIPT && it.isNotEmpty() }

    /** The take's session id, fixed once so a stale pipeline can never write into a later take. */
    var sessionId: String? = null
        private set

    private val session = RecordingSession { sample, change, clock ->
        val id = sessionId ?: return@RecordingSession
        if (change is Change.SignalObserved) sink.signal(id, change.signal, clock)
        else sink.scriptEvent(id, sample, change, clock)
    }
    val scriptController: ScriptCaptureController? = this.script?.let { ScriptCaptureController(it, session) }
    private val fillers = fillers(session)
    private val takes = takes(session, mode)
    private var committedThroughMs = Long.MIN_VALUE
    private var faceInFrame: Boolean? = null
    private var gazeCount = 0

    /** Called once the capture request is durable, before CameraX starts. */
    fun attach(session: String) {
        if (sessionId != null) return
        sessionId = session
        // The matcher's own initial progress was published before any session existed.
        scriptController?.let {
            sink.scriptEvent(session, 0, Change.ScriptProgressObserved(it.progress.copy(reason = ScriptProgressReason.INITIAL)),
                ClockDomain.CAPTURE_ESTIMATE)
        }
    }

    /**
     * Accepts a committed caption snapshot. Committed captions are stable and chronological, so
     * each one is forwarded exactly once however often the growing snapshot is redelivered.
     */
    fun onCommittedSegments(segments: List<CaptionSegment>) {
        // Unconsumed captions stay in the growing snapshot and are replayed once the session exists.
        if (sessionId == null) return
        segments.filter { it.endMs > committedThroughMs }.sortedBy { it.endMs }.forEach { caption ->
            if (caption.endMs <= committedThroughMs) return@forEach
            committedThroughMs = caption.endMs
            val segment = caption.toSignal() ?: return@forEach
            // The transcript is journaled before anything derived from it.
            session.signal(segment)
            scriptController?.consume(listOf(caption))
            takes.onSegment(segment)
            scriptController?.let { takes.onScriptProgress(segment, it.progress) }
            fillers.onSegment(segment)
        }
    }

    /**
     * Records face presence changes after capture start, like the coalesced vision journal.
     * [onCamera][GazeSample.onCamera] stays unknown: the off-axis hint is head pose, not gaze.
     */
    fun onFace(observation: FaceObservation?, recordingStartedAtMs: Long, nowMs: Long) {
        if (recordingStartedAtMs <= 0L) return
        val observedAt = observation?.timestampMs ?: nowMs
        if (observedAt < recordingStartedAtMs) return
        val face = observation != null
        if (face == faceInFrame) return
        faceInFrame = face
        session.signal(GazeSample("gaze-${++gazeCount}", recordingTimeline.samplesFromMillis(observedAt - recordingStartedAtMs), face))
    }

    private fun CaptionSegment.toSignal(): TranscriptSegment? {
        val text = text.trim()
        if (text.isEmpty() || startMs < 0 || endMs < startMs) return null
        val start = recordingTimeline.samplesFromMillis(startMs)
        val end = recordingTimeline.samplesFromMillis(endMs)
        // Word evidence is optional; a malformed timing list must not cost the segment itself.
        val valid = words.all {
            it.startMs >= startMs && it.endMs > it.startMs && it.endMs <= endMs && it.text.isNotBlank() &&
                it.confidence.isFinite() && it.confidence in 0f..1f
        }
        val timed = if (valid) words.map {
            TranscriptWord(recordingTimeline.samplesFromMillis(it.startMs), recordingTimeline.samplesFromMillis(it.endMs),
                it.text, it.confidence)
        } else emptyList()
        return TranscriptSegment("$start-$end", start, end, text, timed)
    }
}
