package com.example.one_take.captions

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import com.example.one_take.VideoStore
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditRepository
import com.example.one_take.editing.LivePauseReconciler
import com.example.one_take.editing.analyzePauses
import com.example.one_take.editing.mergeDetectedPauses
import com.example.one_take.vision.FaceTrackRepository
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.toApp
import com.example.one_take.features.CaptionFeatureStore
import java.io.File
import kotlinx.coroutines.*

/** Application-scoped jobs survive navigation and Activity recreation, but never modify raw video. */
internal class CaptionJobs private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feature = CaptionFeatureStore.get(app)
    private val repository = CaptionRepository(app)
    private val editRepository = EditRepository(app)
    private val faceRepository = FaceTrackRepository(app)
    private val projects = EngineProjectStore(app)
    private val cleanup = scope.async(Dispatchers.IO) {
        val directory = VideoStore(app.filesDir).directory
        // Only sweep this exporter's private temporary names, once per process,
        // before any new export can start. Originals never use a leading dot.
        directory.listFiles()?.filter {
            it.name.startsWith(".video_") && it.name.contains(".mp4.") && it.name.endsWith(".mp4")
        }?.forEach { it.delete() }
        repository.pruneMissingSources(directory)
        editRepository.pruneMissingSources(directory)
        faceRepository.pruneMissingSources()
    }
    private var job: Job? = null
    private var live: LiveCaptionSession? = null
    private var liveCaptionsEnabled = false
    private var finalized: CompletableDeferred<File>? = null
    private var referenceAudio: Deferred<FloatArray>? = null
    /** Monotonic stage timings for target-device benchmarks; never inferred from UI polling. */
    var referenceDecodeMs = 0L
        private set
    var alignmentMs = 0L
        private set
    var exportMs = 0L
        private set
    var stopToCaptionsMs = 0L
        private set
    private var stoppedAtMs: Long? = null
    var liveText by mutableStateOf<String?>(null)
        private set
    var liveSegments by mutableStateOf<List<CaptionSegment>>(emptyList())
        private set
    var liveWindowCount by mutableIntStateOf(0)
        private set
    var liveFallbackReason: String? = null
        private set
    var usedLiveCaptions by mutableStateOf(false)
        private set
    /** The capture can keep running with pause detection while live captions are disabled. */
    val liveActive get() = liveCaptionsEnabled && live != null

    /** Start before CameraX so its original recording retains microphone priority. */
    fun startLive(engineSession: () -> String? = { null }): Boolean {
        if (busy) return false
        usedLiveCaptions = false
        liveWindowCount = 0
        liveFallbackReason = null
        liveText = null
        liveSegments = emptyList()
        referenceDecodeMs = 0
        alignmentMs = 0
        exportMs = 0
        stopToCaptionsMs = 0
        stoppedAtMs = null
        val captionsEnabled = feature.installed && feature.enabled
        lateinit var session: LiveCaptionSession
        session = LiveCaptionSession(
            // The microphone keeps an immutable candidate snapshot for finalization.  Do not
            // submit an unconfirmed candidate before CameraX has recorded CaptureStarted.  Once
            // a session is available, replay the complete snapshot so a missed startup candidate
            // and the current callback share the coordinator's ordered, deduplicated path.
            LiveMicrophone(app) {
                engineSession()?.let { id ->
                    val snapshot = session.microphone.pauseCandidates()
                    scope.launch(Dispatchers.Default) {
                        runCatching {
                            LiveCaptureCoordinator.get(app).drainPauseCandidates(id, snapshot)
                        }
                    }
                }
            },
        )
        if (!session.microphone.start()) {
            liveFallbackReason = session.microphone.failure ?: "Live microphone unavailable"
            return false
        }
        live = session
        liveCaptionsEnabled = captionsEnabled
        val resultFile = CompletableDeferred<File>()
        finalized = resultFile
        sourcePath = null
        error = null
        exportedFile = null
        liveText = null
        liveWindowCount = 0
        usedLiveCaptions = false
        liveFallbackReason = null
        referenceDecodeMs = 0
        alignmentMs = 0
        exportMs = 0
        stopToCaptionsMs = 0
        stoppedAtMs = null
        feature.inUse = captionsEnabled
        operation = "live"
        job = scope.launch {
            try {
                val provisional = if (captionsEnabled) {
                    session.transcribe(feature.modelFile) { segments ->
                        withContext(Dispatchers.Main.immediate) {
                            liveText = segments.lastOrNull()?.text
                            liveSegments = segments.toList()
                            engineSession()?.let { LiveCaptureCoordinator.get(app).transcript(it, segments) }
                            liveWindowCount = session.completedWindows
                        }
                    }
                } else {
                    emptyList()
                }
                val source = resultFile.await()
                sourcePath = source.absolutePath
                if (!captionsEnabled) {
                    // The microphone still owns the pause detector when captions are off.  Wait
                    // for its final read before aligning candidates to the saved recording.
                    session.microphone.awaitStopped()
                    operation = "analyze"
                } else {
                    operation = "transcribe"
                }
                val reference = referenceAudio?.await() ?: AudioDecoder.decodeMono16k(source)
                val confirmed = withContext(Dispatchers.Default) { analyzePauses(source, reference, app) }
                val edits = reconcileLivePauses(source, reference, session, confirmed)
                if (captionsEnabled) {
                    val alignmentStarted = SystemClock.elapsedRealtime()
                    val aligned = withContext(Dispatchers.Default) { session.reconcile(reference, provisional) }
                    alignmentMs = SystemClock.elapsedRealtime() - alignmentStarted
                    usedLiveCaptions = aligned != null
                    liveFallbackReason = if (aligned == null) session.failure ?: session.microphone.failure
                        ?: "Live audio did not match the recording" else null
                    val segments = aligned ?: WhisperEngine().withSession(feature.modelFile) { it.transcribe(reference) }
                    withContext(Dispatchers.IO) {
                        projects.saveCaptions(source, segments)
                        if (projects.read(source)?.edits == null) {
                            projects.saveEdits(source, edits)
                        }
                    }
                    revision++
                    stopToCaptionsMs = stoppedAtMs?.let { SystemClock.elapsedRealtime() - it } ?: 0
                    feature.inUse = false
                    exportCopy(source, segments)
                } else {
                    withContext(Dispatchers.IO) {
                        if (projects.read(source)?.edits == null) projects.saveEdits(source, edits)
                    }
                    revision++
                    stopToCaptionsMs = stoppedAtMs?.let { SystemClock.elapsedRealtime() - it } ?: 0
                }
                feature.inUse = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: if (captionsEnabled) {
                    "Caption generation failed. Please retry."
                } else {
                    "Pause analysis failed. Please retry."
                }
            } finally {
                session.microphone.stop()
                withContext(NonCancellable) { session.microphone.awaitStopped() }
                live = null
                liveCaptionsEnabled = false
                finalized = null
                referenceAudio?.cancel()
                referenceAudio = null
                liveText = null
                liveSegments = emptyList()
                feature.inUse = false
                operation = null
                job = null
            }
        }
        return true
    }

    private suspend fun reconcileLivePauses(
        source: File,
        reference: FloatArray,
        session: LiveCaptionSession,
        confirmed: EditDecision,
    ): EditDecision {
        val offset = withContext(Dispatchers.Default) { session.pauseOffsetMs(reference) }
        val candidates = withContext(Dispatchers.IO) {
            // The project ledger is the durable source of truth.  A callback that was still
            // queued when the microphone stopped must not silently become a local-only edit.
            projects.read(source)?.pauseCandidates.orEmpty()
        }
        return withContext(Dispatchers.Default) {
            LivePauseReconciler.reconcile(candidates, offset, confirmed)
        }
    }

    /**
     * Stops the microphone and durably hands every detector candidate to the capture ledger.
     *
     * The microphone starts before CameraX has a session id, so its retained detector snapshot
     * is the bridge for candidates observed during that startup window.  The coordinator replays
     * that snapshot on its single dispatcher and deduplicates candidates already on the ledger.
     */
    fun captureFinalizationBarrier(): (suspend (String?) -> Unit)? {
        val session = live ?: return null
        return { sessionId -> drainLiveCapture(session, sessionId) }
    }

    private suspend fun drainLiveCapture(session: LiveCaptionSession, sessionId: String?) {
        withContext(Dispatchers.Main.immediate) {
            if (live === session) noteStopRequested()
        }
        session.microphone.stop()
        session.microphone.awaitStopped()

        val candidates = session.microphone.pauseCandidates()
        if (sessionId != null && candidates.isNotEmpty()) {
            val coordinator = LiveCaptureCoordinator.get(app)
            // CameraX reports Start before recorder finalization, but the coordinator records
            // that callback asynchronously.  Drain that lifecycle queue before replaying
            // recognizer candidates so a pre-start candidate is never mistaken for an active
            // capture or silently raced by SourceFinalized.
            coordinator.flush()
            coordinator.drainPauseCandidates(sessionId, candidates)
            coordinator.flush()
        }
    }

    fun noteStopRequested() {
        if (live != null && stoppedAtMs == null) stoppedAtMs = SystemClock.elapsedRealtime()
    }

    fun stopLive() {
        noteStopRequested()
        live?.microphone?.stop()
    }

    fun finishLive(source: File): Boolean {
        if (live == null) return false
        if (finalized?.isCompleted == true) return true
        referenceAudio = scope.async(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            AudioDecoder.decodeMono16k(source).also {
                referenceDecodeMs = SystemClock.elapsedRealtime() - started
            }
        }
        sourcePath = source.absolutePath
        operation = if (liveCaptionsEnabled) "transcribe" else "analyze"
        stopLive()
        finalized?.complete(source)
        return true
    }

    fun abortUnfinalizedLive() {
        if (finalized?.isCompleted != true) abortLive()
    }

    fun abortLive() {
        if (live == null) return
        stopLive()
        job?.cancel()
    }
    var sourcePath by mutableStateOf<String?>(null)
        private set
    var operation by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var exportedFile by mutableStateOf<File?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    val busy get() = operation != null

    fun generate(source: File, autoExport: Boolean = false) {
        if (busy || !feature.installed) return
        usedLiveCaptions = false
        liveWindowCount = 0
        liveFallbackReason = "Captions generated from the saved recording"
        referenceDecodeMs = 0
        alignmentMs = 0
        exportMs = 0
        stopToCaptionsMs = 0
        sourcePath = source.absolutePath
        operation = "transcribe"
        error = null
        exportedFile = null
        feature.inUse = true
        job = scope.launch {
            try {
                val audio = AudioDecoder.decodeMono16k(source)
                val segments = WhisperEngine().withSession(feature.modelFile) { it.transcribe(audio) }
                val edits = withContext(Dispatchers.Default) { analyzePauses(source, audio, app) }
                withContext(Dispatchers.IO) {
                    projects.saveCaptions(source, segments)
                    if (projects.read(source)?.edits == null) projects.saveEdits(source, edits)
                }
                revision++
                feature.inUse = false
                if (autoExport) exportCopy(source, segments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: "Caption generation failed. Please retry."
            } finally {
                feature.inUse = false
                operation = null
                job = null
            }
        }
    }

    suspend fun read(source: File): List<CaptionSegment>? = withContext(Dispatchers.IO) { projects.read(source)?.captions?.map { it.toApp() } }

    suspend fun save(source: File, segments: List<CaptionSegment>) {
        check(!busy) { "Wait for the current export to finish" }
        withContext(Dispatchers.IO) { projects.saveCaptions(source, segments) }
        if (sourcePath == source.absolutePath) exportedFile = null
        revision++
    }

    suspend fun readEdits(source: File): EditDecision? = withContext(Dispatchers.IO) { projects.read(source)?.edits?.toApp() }

    suspend fun saveEdits(source: File, edits: EditDecision) {
        check(!busy) { "Wait for the current export to finish" }
        withContext(Dispatchers.IO) { projects.saveEdits(source, edits) }
        if (sourcePath == source.absolutePath) exportedFile = null
        revision++
    }

    fun detectCuts(source: File) {
        if (busy) return
        sourcePath = source.absolutePath
        operation = "analyze"
        error = null
        exportedFile = null
        job = scope.launch {
            try {
                val audio = AudioDecoder.decodeMono16k(source)
                val edits = withContext(Dispatchers.Default) { analyzePauses(source, audio, app) }
                withContext(Dispatchers.IO) {
                    val previous = projects.read(source)?.edits?.toApp()
                    projects.saveEdits(source, mergeDetectedPauses(previous, edits))
                }
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: "Pause analysis failed. Please retry."
            } finally {
                operation = null
                job = null
            }
        }
    }

    fun forgetExport(source: File) {
        if (!busy && sourcePath == source.absolutePath) exportedFile = null
    }

    fun export(source: File, segments: List<CaptionSegment>) {
        if (busy) return
        sourcePath = source.absolutePath
        operation = "export"
        progress = 0
        error = null
        exportedFile = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { projects.saveCaptions(source, segments) }
                exportCopy(source, segments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: "Caption export failed. Please retry."
            } finally {
                operation = null
                job = null
            }
        }
    }

    private suspend fun exportCopy(source: File, segments: List<CaptionSegment>) {
        operation = "export"
        progress = 0
        var pending: VideoStore.PendingRecording? = null
        try {
            cleanup.await()
            val handle = withContext(Dispatchers.IO) { VideoStore(app.filesDir).createPendingRecording() }
            pending = handle
            val started = SystemClock.elapsedRealtime()
            val edits = withContext(Dispatchers.IO) { projects.read(source)?.edits?.toApp() }
            val preset = CaptionStyleStore.get(app).preset
            val output = CaptionExporter(app).export(source, segments, handle.outputFile, edits, preset) { progress = it }
            exportMs = SystemClock.elapsedRealtime() - started
            handle.complete()
            pending = null
            exportedFile = output
            revision++
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { pending?.discard() }
        }
    }

    suspend fun removeMetadata(source: File) = withContext(Dispatchers.IO) {
        repository.remove(source)
        editRepository.remove(source)
        faceRepository.remove(source)
        projects.remove(source)
        LiveCaptureCoordinator.get(app).remove(source)
    }

    fun cancel() { stopLive(); job?.cancel() }

    companion object {
        @Volatile private var instance: CaptionJobs? = null
        fun get(context: Context): CaptionJobs = instance ?: synchronized(this) {
            instance ?: CaptionJobs(context).also { instance = it }
        }
    }
}
