package com.example.one_take.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.recordingFingerprint
import com.example.one_take.vision.FaceObservation
import com.onetake.engine.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Application-scoped, serial I/O boundary for capture events. Never owns or deletes raw media. */
internal class LiveCaptureCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "live-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = LiveCaptureStore(File(app.noBackupFilesDir, "capture_ledgers"))
    private val projects = EngineProjectStore(app)
    private data class ActiveClock(val requestedAt: Long, var startedAt: Long? = null)
    private val active = mutableMapOf<String, ActiveClock>()
    private data class VisionUpdate(val observation: FaceObservation?, val observedAt: Long)
    private val pendingVision = ConcurrentHashMap<String, VisionUpdate>()
    private val visionScheduled = AtomicBoolean(false)
    private var pauseCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    fun pauseCount(session: String?): Int = pauseCounts[session] ?: 0
    var error by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            store.sessions().forEach { session -> guarded {
                val state = store.snapshot(session)
                if (state.phase == SessionPhase.RECORDING || state.phase == SessionPhase.FINALIZING) {
                    val lastPosition = store.events(session).filter { it.clock == ClockDomain.CAPTURE_ESTIMATE }
                        .maxOfOrNull { it.sample } ?: 0
                    store.append(session, lastPosition, Change.CaptureInterrupted("process_restarted"), ClockDomain.CAPTURE_ESTIMATE)
                }
            } }
        }
    }

    /** Returns only after the requested event is durable, before CameraX may start. */
    suspend fun begin(source: File, mode: SessionMode = SessionMode.ASSISTED, script: String? = null): String = withContext(dispatcher) {
        val session = store.begin(source.name, mode, script, System.currentTimeMillis())
        active[session] = ActiveClock(SystemClock.elapsedRealtime())
        withContext(Dispatchers.Main) { pauseCounts = emptyMap() }
        session
    }

    fun started(session: String) {
        val observedAt = SystemClock.elapsedRealtime()
        scope.launch { guarded {
            val clock = active[session] ?: return@guarded
            val state = store.snapshot(session)
            if (!state.captureStarted && state.phase in runningPhases) {
                store.append(session, 0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
                clock.startedAt = observedAt
            }
        } }
    }

    fun stop(session: String, reason: String) {
        val observedAt = SystemClock.elapsedRealtime()
        scope.launch { guarded {
            val clock = active[session] ?: return@guarded
            if (store.snapshot(session).phase == SessionPhase.RECORDING) {
                store.append(session, position(clock, observedAt), Change.StopRequested(reason), ClockDomain.CAPTURE_ESTIMATE)
            }
        } }
    }

    fun failed(session: String, reason: String) {
        val observedAt = SystemClock.elapsedRealtime()
        scope.launch { guarded {
            val clock = active.remove(session) ?: return@guarded
            pendingVision.remove(session)
            if (store.snapshot(session).phase in runningPhases) {
                store.append(session, position(clock, observedAt), Change.CaptureFailed(reason), ClockDomain.CAPTURE_ESTIMATE)
            }
        } }
    }

    fun interrupted(session: String, reason: String) {
        val observedAt = SystemClock.elapsedRealtime()
        scope.launch { guarded {
            val clock = active.remove(session) ?: return@guarded
            pendingVision.remove(session)
            if (store.snapshot(session).phase in runningPhases) {
                store.append(session, position(clock, observedAt), Change.CaptureInterrupted(reason), ClockDomain.CAPTURE_ESTIMATE)
            }
        } }
    }

    fun cancelled(session: String) {
        scope.launch { guarded {
            active.remove(session)
            pendingVision.remove(session)
            if (store.snapshot(session).phase in runningPhases) {
                store.append(session, 0, Change.Cancelled, ClockDomain.MEDIA)
            }
        } }
    }

    fun scriptEvent(session: String, sample: Long, change: Change, clock: ClockDomain) {
        require(change is Change.ScriptProgressObserved || change is Change.SignalObserved)
        scope.launch { guarded {
            if (session !in active) return@guarded
            if (store.snapshot(session).phase in runningPhases) store.append(session, sample, change, clock)
        } }
    }

    /**
     * Records a live signal in its own clock, or a media-confirmed one once the source is READY.
     * Redelivery of the same signal id is ignored rather than surfaced as a history failure.
     */
    fun signal(session: String, signal: SessionSignal, clock: ClockDomain = signal.liveClock) {
        scope.launch { guarded {
            val state = store.snapshot(session)
            val accepting = if (clock == ClockDomain.MEDIA) state.phase == SessionPhase.READY
                else session in active && state.captureStarted && state.phase in runningPhases
            if (accepting && signal.key(clock) !in state.signalKeys) {
                store.append(session, signal.endSample, Change.SignalObserved(signal), clock)
            }
        } }
    }

    suspend fun session(session: String): RecordingSessionSnapshot = withContext(dispatcher) { store.session(session) }

    /** Unreconciled ASR stays in its own clock domain and never changes final captions. */
    fun transcript(session: String, segments: List<CaptionSegment>) {
        val last = segments.lastOrNull() ?: return
        val text = last.text.take(4_000)
        val at = recordingTimeline.samplesFromMillis(last.endMs.coerceAtLeast(0))
        scope.launch { guarded {
            if (session !in active) return@guarded
            val state = store.snapshot(session)
            if (state.captureStarted && state.phase in runningPhases && state.provisionalText != text) {
                store.append(session, at, Change.ProvisionalTranscript(text), ClockDomain.RECOGNIZER)
            }
        } }
    }

    /** Candidate timing remains in microphone units until saved audio confirms it. */
    fun pauseCandidate(session: String, candidate: PauseCandidate) {
        scope.launch { guarded { appendPauseCandidate(session, candidate) } }
    }

    /** Durably submits the complete detector snapshot before source finalization. */
    suspend fun drainPauseCandidates(session: String, candidates: List<PauseCandidate>) =
        withContext(dispatcher) {
            if (session !in active) return@withContext
            val state = store.snapshot(session)
            if (state.phase !in runningPhases) return@withContext
            // A microphone can observe audio while CameraX is still starting.  Such candidates
            // are retained by CaptionJobs, but a session that never emitted CaptureStarted must
            // not be promoted to an active capture just to accept recognizer observations.
            if (!state.captureStarted) return@withContext
            candidates
                .distinctBy(PauseCandidate::id)
                .forEach { appendPauseCandidate(session, it) }
        }

    private suspend fun appendPauseCandidate(session: String, candidate: PauseCandidate) {
        if (session !in active) return
        val state = store.snapshot(session)
        if (!state.captureStarted || state.phase !in runningPhases) return
        if (state.pauseCandidates.any { it.id == candidate.id }) return
        store.append(session, candidate.endSample, Change.PauseCandidateObserved(candidate), ClockDomain.RECOGNIZER)
        withContext(Dispatchers.Main) {
            pauseCounts = pauseCounts + (session to state.pauseCandidates.size + 1)
        }
    }

    /** Coalescing bounds pending vision work if disk I/O falls behind camera analysis. */
    fun vision(session: String, observation: FaceObservation?) {
        pendingVision[session] = VisionUpdate(observation, observation?.timestampMs ?: SystemClock.elapsedRealtime())
        drainVision()
    }

    private fun drainVision() {
        if (!visionScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                pendingVision.entries.toList().forEach { (session, update) ->
                    if (!pendingVision.remove(session, update)) return@forEach
                    guarded {
                        val clock = active[session] ?: return@guarded
                        val start = clock.startedAt ?: return@guarded
                        if (update.observedAt < start) return@guarded
                        val state = store.snapshot(session)
                        if (!state.captureStarted || state.phase !in runningPhases) return@guarded
                        val face = update.observation
                        val signal = VisionObservation(face != null, "CPU", face?.centerX, face?.centerY, face?.offAxis ?: false)
                        if (state.lastVision != signal) {
                            store.append(session, position(clock, update.observedAt), Change.VisionObserved(signal), ClockDomain.CAPTURE_ESTIMATE)
                        }
                    }
                }
            } finally {
                visionScheduled.set(false)
                if (pendingVision.isNotEmpty()) drainVision()
            }
        }
    }

    suspend fun finalized(session: String, source: File) = withContext(dispatcher) {
        try {
            val before = store.snapshot(session)
            require(before.captureSourceName == source.name) { "Finalized file belongs to another capture" }
            if (before.phase != SessionPhase.READY) {
                val duration = mediaDurationSamples(source)
                store.append(session, duration,
                    Change.SourceFinalized(recordingFingerprint(source), duration, VideoAnchor(0, 0)), ClockDomain.MEDIA)
            }
            projects.adoptCapture(source, store.events(session))
        } catch (exception: Exception) {
            // A failed media probe or append must not leave a completed camera
            // session marked active forever. Retain a recoverable history when
            // the journal itself is still writable.
            try {
                val state = store.snapshot(session)
                if (state.phase in runningPhases && state.captureSourceName == source.name) {
                    val clock = active[session]
                    val at = clock?.let { position(it, SystemClock.elapsedRealtime()) } ?: 0L
                    store.append(session, at, Change.CaptureInterrupted("finalization_failed"), ClockDomain.CAPTURE_ESTIMATE)
                }
            } catch (journalFailure: Exception) { exception.addSuppressed(journalFailure) }
            throw exception
        } finally {
            active.remove(session)
            pendingVision.remove(session)
        }
    }

    /** Called only after VideoStore has inspected pending media and released its markers. */
    suspend fun recover(videos: List<File>) = withContext(dispatcher) {
        val byName = videos.associateBy { it.name }
        store.sessions().forEach { session -> guarded {
            if (session in active) return@guarded
            var state = store.snapshot(session)
            if (state.phase in runningPhases) {
                // Camera ownership has ended even if a prior terminal append
                // failed. Retry the durable interruption after storage recovers.
                val at = store.events(session).filter { it.clock == ClockDomain.CAPTURE_ESTIMATE }
                    .maxOfOrNull { it.sample } ?: 0L
                store.append(session, at, Change.CaptureInterrupted("capture_owner_lost"), ClockDomain.CAPTURE_ESTIMATE)
                state = store.snapshot(session)
            }
            if (state.phase != SessionPhase.INTERRUPTED && state.phase != SessionPhase.READY) return@guarded
            val source = byName[state.captureSourceName] ?: return@guarded
            // An undecidable partial file stays interrupted; metadata probing may throw.
            finalized(session, source)
        } }
    }

    suspend fun history(session: String): List<Event> = withContext(dispatcher) { store.events(session) }
    suspend fun snapshot(session: String): EngineState = withContext(dispatcher) { store.snapshot(session) }
    suspend fun flush() = withContext(dispatcher) { Unit }
    suspend fun remove(source: File) = withContext(dispatcher) { store.removeForSource(source.name) }

    private fun position(clock: ActiveClock, observedAt: Long) = recordingTimeline.samplesFromMillis(
        (observedAt - (clock.startedAt ?: clock.requestedAt)).coerceAtLeast(0))

    private fun mediaDurationSamples(source: File): Long {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(source.absolutePath)
            val ms = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(ms != null && ms > 0 && width > 0 && height > 0) { "Finalized media has no playable video track" }
            return recordingTimeline.samplesFromMillis(ms)
        } finally { metadata.release() }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) {
            Log.e("LiveEngine", "Capture history operation failed", exception)
            withContext(Dispatchers.Main) { error = "Recording history could not be saved. Your original video is safe." }
        }
    }

    companion object {
        private val runningPhases = setOf(SessionPhase.RECORDING, SessionPhase.FINALIZING)
        @Volatile private var instance: LiveCaptureCoordinator? = null
        fun get(context: Context): LiveCaptureCoordinator = instance ?: synchronized(this) {
            instance ?: LiveCaptureCoordinator(context).also { instance = it }
        }
    }
}
