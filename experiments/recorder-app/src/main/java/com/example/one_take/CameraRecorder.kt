package com.example.one_take

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.example.one_take.vision.FaceObservation
import com.example.one_take.vision.FaceSample
import com.example.one_take.vision.FaceTrackRepository
import com.example.one_take.vision.FaceTracker
import com.example.one_take.engine.LiveCaptureCoordinator
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates CameraX preview and recording for the capture screen.
 *
 * [captureState] is the single state source for the UI. It changes to
 * [CaptureUiState.Starting] before CameraX receives the pending recording, so
 * a second start or a lens switch cannot be accepted during that interval.
 */
internal class CameraRecorder(private val context: Context) {
    private companion object {
        const val TAG = "CameraRecorder"
    }

    interface Listener {
        fun onCameraReady()
        fun onCameraError(message: String)
        fun onRecordingFinalized(file: File)
        fun onRecordingError(message: String)
    }

    val videoStore = VideoStore(context.filesDir)

    var captureState by mutableStateOf(CaptureUiState.Idle)
        private set

    /** Video sizes that the currently selected lens can actually record. */
    var supportedQualities by mutableStateOf<List<CameraQuality>>(emptyList())
        private set

    /** The quality used by the currently bound VideoCapture use case. */
    var selectedQuality by mutableStateOf(CameraQuality.FHD)
        private set

    var minZoomRatio by mutableFloatStateOf(1f)
        private set

    var maxZoomRatio by mutableFloatStateOf(1f)
        private set

    var zoomRatio by mutableFloatStateOf(1f)
        private set

    /** Elapsed-realtime timestamp captured when CameraX reports Start. */
    var recordingStartedAt by mutableLongStateOf(0L)
        private set

    /** Latest face observation in normalized upright preview coordinates. */
    var faceObservation by mutableStateOf<FaceObservation?>(null)
        private set

    /** Non-fatal vision failure. Camera capture remains available when set. */
    var visionError by mutableStateOf<String?>(null)
        private set

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureCoordinator = LiveCaptureCoordinator.get(context)
    private val faceTrackRepository = FaceTrackRepository(context)
    private val faceTracker = FaceTracker(
        context = context,
        onObservation = ::handleFaceObservation,
        onError = ::handleVisionError
    )
    private var listener: Listener? = null
    private var provider: ProcessCameraProvider? = null
    private var providerRequested = false
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var requestedQuality = CameraQuality.FHD
    private var bindToken = 0L
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var faceAnalysis: ImageAnalysis? = null
    private var visionAnalysisEnabled = true
    private var observedZoomState: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { zoomState ->
        if (!released) updateZoomState(zoomState)
    }
    private var recording: Recording? = null
    private var pendingRecording: VideoStore.PendingRecording? = null
    private var beginPending: VideoStore.PendingRecording? = null
    private var finalizationBarrier: (suspend (String?) -> Unit)? = null
    private var pendingFinalizationBarrier: (suspend (String?) -> Unit)? = null
    private val engineSessions = IdentityHashMap<VideoStore.PendingRecording, String>()
    private var beginGeneration = 0L
    private var activePending: VideoStore.PendingRecording? = null
    private val faceSamples = ArrayList<FaceSample>()
    private var faceSampleSource: File? = null
    private var released = false

    /** The durable engine session currently associated with the active capture. */
    var activeEngineSessionId by mutableStateOf<String?>(null)
        private set

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Installs the per-take metadata drain that must complete before source adoption. */
    fun setFinalizationBarrier(barrier: (suspend (String?) -> Unit)?) {
        if (!released) finalizationBarrier = barrier
    }

    /**
     * Recovers outputs left with pending markers by an earlier process.
     * The blocking metadata validation runs on the IO dispatcher.
     */
    suspend fun recoverVideos(): List<File> = withContext(Dispatchers.IO) {
        videoStore.recoverPendingFiles(::isPlayableVideo)
        faceTrackRepository.pruneMissingSources()
        val videos = videoStore.listVideos()
        try {
            captureCoordinator.recover(videos)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to recover capture engine sessions", exception)
        }
        videos
    }

    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        quality: CameraQuality = CameraQuality.FHD
    ) {
        if (released || captureState != CaptureUiState.Idle) return

        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        this.lensFacing = lensFacing
        this.requestedQuality = quality
        val token = ++bindToken

        provider?.let {
            bindUseCases(
                it,
                token,
                preferThirtyFps = true,
                requestedQuality = quality,
                includeVision = visionAnalysisEnabled
            )
            return
        }

        if (providerRequested) return
        providerRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (released) return@addListener
            try {
                provider = providerFuture.get()
                bindUseCases(
                    provider ?: return@addListener,
                    bindToken,
                    preferThirtyFps = true,
                    requestedQuality = requestedQuality,
                    includeVision = visionAnalysisEnabled
                )
            } catch (_: Exception) {
                providerRequested = false
                listener?.onCameraError(context.getString(R.string.error_camera_start_failed))
            }
        }, mainExecutor)
    }

    fun unbindCamera() {
        bindToken++
        clearZoomObserver(resetState = true)
        faceTracker.invalidateAnalysis()
        faceAnalysis = null
        faceObservation = null
        videoCapture = null
        camera = null
        previewView = null
        lifecycleOwner = null
        provider?.unbindAll()
    }

    /**
     * Rebinds the preview and recorder with [quality] when no recording is in
     * progress. The caller can also pass the same value to [bindCamera] after
     * changing a saved UI preference.
     */
    fun selectQuality(quality: CameraQuality): Boolean {
        if (released || captureState != CaptureUiState.Idle) return false
        val provider = provider ?: return false
        if (previewView == null || lifecycleOwner == null) return false
        requestedQuality = quality
        val token = ++bindToken
        bindUseCases(
            provider,
            token,
            preferThirtyFps = true,
            requestedQuality = quality,
            includeVision = visionAnalysisEnabled
        )
        return true
    }

    /**
     * Sets the camera zoom while it is safe to mutate the bound use case.
     * Values outside the device range are clamped; NaN and infinities are
     * rejected so they cannot reach CameraX.
     */
    fun setZoomRatio(value: Float): Boolean {
        if (released || (captureState != CaptureUiState.Idle &&
                captureState != CaptureUiState.Recording)) {
            return false
        }
        if (!value.isFinite()) return false
        val camera = camera ?: return false
        val clamped = value.coerceIn(minZoomRatio, maxZoomRatio)
        camera.cameraControl.setZoomRatio(clamped)
        zoomRatio = clamped
        return true
    }

    fun startRecording(): Boolean {
        if (released || captureState != CaptureUiState.Idle) return false

        val capture = videoCapture
        if (capture == null) {
            listener?.onRecordingError(context.getString(R.string.error_camera_still_starting))
            return false
        }

        val pending = try {
            videoStore.createPendingRecording()
        } catch (_: IOException) {
            listener?.onRecordingError(context.getString(R.string.error_video_directory_unavailable))
            return false
        }

        pendingRecording = pending
        beginPending = pending
        pendingFinalizationBarrier = finalizationBarrier
        val generation = ++beginGeneration
        faceSampleSource = pending.outputFile
        faceSamples.clear()
        recordingStartedAt = 0L
        // Set this before prepareRecording(...).start(...) so the UI blocks
        // duplicate taps immediately, even while CameraX is still starting.
        captureState = CaptureUiState.Starting

        // The engine ledger must contain the capture request before CameraX is
        // allowed to open the encoder.  Keep this work off the main thread and
        // re-check the pending recording identity before starting CameraX.
        finalizeScope.launch {
            val sessionId = try {
                captureCoordinator.begin(pending.outputFile)
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to begin capture engine session", exception)
                null
            }
            mainExecutor.execute {
                beginPending = null
                if (sessionId == null) {
                    if (pendingRecording === pending) {
                        failPendingBeforeStart(
                            pending,
                            generation,
                            context.getString(R.string.error_recording_start_failed)
                        )
                    }
                    return@execute
                }

                if (released || pendingRecording !== pending ||
                    beginGeneration != generation || captureState != CaptureUiState.Starting
                ) {
                    cancelPendingBeforeStart(pending, sessionId, generation)
                    return@execute
                }

                engineSessions[pending] = sessionId
                activePending = pending
                activeEngineSessionId = sessionId
                startCameraRecording(pending, sessionId, generation, capture)
            }
        }
        return true
    }

    fun stopRecording(reason: String = "user_stop") {
        if (released) return
        if (captureState == CaptureUiState.Starting || captureState == CaptureUiState.Recording) {
            captureState = CaptureUiState.Finalizing
            engineSessionFor(pendingRecording)?.let { sessionId ->
                try {
                    captureCoordinator.stop(sessionId, reason)
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to record capture stop", exception)
                }
            }
            recording?.stop()
        }
    }

    fun release() {
        if (released) return
        released = true
        bindToken++
        listener = null
        finalizationBarrier = null
        clearZoomObserver(resetState = true)
        faceTracker.invalidateAnalysis()

        engineSessionFor(pendingRecording)?.let { sessionId ->
            try {
                captureCoordinator.stop(sessionId, "activity_disposed")
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to record activity disposal", exception)
            }
        }

        if (recording != null) {
            // Keep pendingRecording and its marker alive until Finalize. This
            // lets a valid file survive Activity destruction and lets the
            // callback close the lock after CameraX finishes writing.
            recording?.stop()
        } else if (pendingRecording != null && beginPending == null &&
            captureState != CaptureUiState.Finalizing
        ) {
            // A start failure before Recording was returned cannot produce a
            // valid output, so its marker can be discarded immediately.
            val pending = pendingRecording
            val sessionId = engineSessionFor(pending)
            if (pending != null && sessionId != null) {
                failEngineSession(sessionId, "activity_disposed_before_start")
                engineSessions.remove(pending)
                pending.discard()
            } else {
                pending?.discard()
            }
            pendingRecording = null
            clearActiveSession(pending)
            clearFaceSamples()
        }

        // Keep the Recording reference until CameraX emits Finalize.  A
        // delayed Start callback can otherwise leave an activity-disposed
        // recording running with no handle to stop it.
        captureState = CaptureUiState.Idle
        recordingStartedAt = 0L
        videoCapture = null
        camera = null
        faceAnalysis = null
        provider?.unbindAll()
        faceTracker.release()
        previewView = null
        lifecycleOwner = null
    }

    private fun startCameraRecording(
        pending: VideoStore.PendingRecording,
        sessionId: String,
        generation: Long,
        capture: VideoCapture<Recorder>,
    ) {
        if (released || pendingRecording !== pending ||
            beginGeneration != generation || captureState != CaptureUiState.Starting
        ) {
            cancelPendingBeforeStart(pending, sessionId, generation)
            return
        }

        try {
            val outputOptions = FileOutputOptions.Builder(pending.outputFile).build()
            val prepared = capture
                .output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
            recording = prepared.start(mainExecutor, ::handleRecordEvent)
            if (captureState == CaptureUiState.Finalizing || released) {
                recording?.stop()
            }
        } catch (exception: SecurityException) {
            failToStartCamera(pending, sessionId, exception,
                context.getString(R.string.error_microphone_permission_required))
        } catch (exception: IOException) {
            failToStartCamera(pending, sessionId, exception,
                context.getString(R.string.error_recording_start_failed))
        } catch (exception: Exception) {
            failToStartCamera(pending, sessionId, exception,
                context.getString(R.string.error_recording_start_failed))
        }
    }

    private fun failToStartCamera(
        pending: VideoStore.PendingRecording,
        sessionId: String,
        exception: Exception,
        message: String,
    ) {
        Log.e(TAG, "Unable to start CameraX recording", exception)
        failEngineSession(sessionId, "camera_start_failed")
        if (pendingRecording === pending) {
            pendingRecording = null
            pending.discard()
            engineSessions.remove(pending)
            clearActiveSession(pending)
            clearFaceSamples()
            recording = null
            captureState = CaptureUiState.Idle
            recordingStartedAt = 0L
            if (!released) listener?.onRecordingError(message)
        }
    }

    private fun failPendingBeforeStart(
        pending: VideoStore.PendingRecording,
        generation: Long,
        message: String,
    ) {
        if (pendingRecording !== pending || beginGeneration != generation) return
        pendingRecording = null
        pending.discard()
        clearActiveSession(pending)
        clearFaceSamples()
        captureState = CaptureUiState.Idle
        recordingStartedAt = 0L
        if (!released) listener?.onRecordingError(message)
    }

    private fun cancelPendingBeforeStart(
        pending: VideoStore.PendingRecording,
        sessionId: String,
        generation: Long,
    ) {
        try {
            captureCoordinator.cancelled(sessionId)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to cancel pending capture engine session", exception)
        }
        if (pendingRecording === pending && beginGeneration == generation) {
            pendingRecording = null
            pending.discard()
            engineSessions.remove(pending)
            clearActiveSession(pending)
            clearFaceSamples()
            captureState = CaptureUiState.Idle
            recordingStartedAt = 0L
            recording = null
            if (!released) listener?.onRecordingError(
                context.getString(R.string.error_recording_start_failed)
            )
        } else {
            // The pending file can only be owned by this begin operation.  If
            // a newer recording has already replaced the current reference,
            // finish this exact handle without touching that newer recording.
            pending.discard()
            engineSessions.remove(pending)
        }
    }

    private fun engineSessionFor(pending: VideoStore.PendingRecording?): String? {
        return pending?.let(engineSessions::get)
    }

    private fun clearActiveSession(pending: VideoStore.PendingRecording?) {
        if (activePending === pending) {
            activePending = null
            activeEngineSessionId = null
        }
    }

    private fun failEngineSession(sessionId: String, reason: String) {
        try {
            captureCoordinator.failed(sessionId, reason)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to record capture failure", exception)
        }
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        token: Long,
        preferThirtyFps: Boolean,
        requestedQuality: CameraQuality,
        includeVision: Boolean
    ) {
        if (released || token != bindToken || captureState != CaptureUiState.Idle) return
        val previewView = previewView ?: return
        val lifecycleOwner = lifecycleOwner ?: return

        try {
            faceTracker.invalidateAnalysis()
            provider.unbindAll()
            clearZoomObserver(resetState = true)
            videoCapture = null
            camera = null
            faceAnalysis = null
            supportedQualities = emptyList()
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val cameraInfo = provider.getCameraInfo(selector)
            val availableQualities = Recorder.getVideoCapabilities(cameraInfo)
                .getSupportedQualities(DynamicRange.SDR)
                .mapNotNull { CameraQuality.fromCameraXQuality(it) }
            supportedQualities = availableQualities
            val actualQuality = resolveQuality(requestedQuality, availableQualities)
                ?: throw IllegalArgumentException("No supported video quality")
            val qualitySelector = QualitySelector.from(actualQuality.cameraXQuality)
            val configuredRecorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val configuredVideoBuilder = VideoCapture.Builder(configuredRecorder)
                .setTargetRotation(targetRotation)
            if (preferThirtyFps) {
                configuredVideoBuilder.setTargetFrameRate(Range(30, 30))
            }
            val configuredVideo = configuredVideoBuilder.build()

            val configuredAnalysis = if (includeVision && visionAnalysisEnabled) {
                faceTracker.createAnalysis(targetRotation)
            } else {
                null
            }

            val boundCamera = if (configuredAnalysis == null) {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    configuredVideo
                )
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    configuredVideo,
                    configuredAnalysis
                )
            }
            videoCapture = configuredVideo
            camera = boundCamera
            faceAnalysis = configuredAnalysis
            selectedQuality = actualQuality
            observeZoomState(boundCamera, lifecycleOwner)
            listener?.onCameraReady()
        } catch (exception: Exception) {
            provider.unbindAll()
            videoCapture = null
            camera = null
            faceAnalysis = null
            clearZoomObserver(resetState = true)
            if (includeVision) {
                visionAnalysisEnabled = false
                visionError = visionError ?: "Face tracking unavailable"
                bindUseCases(
                    provider,
                    token,
                    preferThirtyFps = preferThirtyFps,
                    requestedQuality = requestedQuality,
                    includeVision = false
                )
            } else if (preferThirtyFps) {
                bindUseCases(
                    provider,
                    token,
                    preferThirtyFps = false,
                    requestedQuality = requestedQuality,
                    includeVision = false
                )
            } else {
                listener?.onCameraError(cameraErrorMessage(exception))
            }
        }
    }

    private fun resolveQuality(
        requested: CameraQuality,
        available: List<CameraQuality>
    ): CameraQuality? {
        if (available.isEmpty()) return null
        return available.firstOrNull { it == requested }
            ?: available
                .filter { it.rank < requested.rank }
                .maxByOrNull(CameraQuality::rank)
            ?: available
                .filter { it.rank > requested.rank }
                .minByOrNull(CameraQuality::rank)
    }

    private fun observeZoomState(camera: Camera, lifecycleOwner: LifecycleOwner) {
        clearZoomObserver(resetState = true)
        val zoomState = camera.cameraInfo.zoomState
        observedZoomState = zoomState
        zoomState.observe(lifecycleOwner, zoomObserver)
        zoomState.value?.let(::updateZoomState)
    }

    private fun clearZoomObserver(resetState: Boolean) {
        observedZoomState?.removeObserver(zoomObserver)
        observedZoomState = null
        if (resetState) {
            minZoomRatio = 1f
            maxZoomRatio = 1f
            zoomRatio = 1f
        }
    }

    private fun updateZoomState(state: ZoomState) {
        minZoomRatio = state.minZoomRatio
        maxZoomRatio = state.maxZoomRatio
        zoomRatio = state.zoomRatio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
    }

    private fun handleFaceObservation(observation: FaceObservation?) {
        if (released) return
        faceObservation = observation
        engineSessionFor(pendingRecording)?.let { sessionId ->
            try {
                captureCoordinator.vision(sessionId, observation)
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to record vision observation", exception)
            }
        }
        if (observation == null || captureState != CaptureUiState.Recording) return

        val startedAt = recordingStartedAt
        if (startedAt <= 0L || faceSampleSource == null) return
        if (observation.timestampMs < startedAt) return
        val timeMs = observation.timestampMs - startedAt
        val sample = FaceSample(
            timeMs = timeMs,
            centerX = observation.centerX,
            centerY = observation.centerY,
            faceWidth = observation.width
        )
        if (faceSamples.lastOrNull()?.timeMs?.let { it <= timeMs } != false) {
            faceSamples += sample
        }
    }

    private fun handleVisionError(message: String) {
        if (released) return
        visionError = message
        visionAnalysisEnabled = false
        // If initialization failed after the analysis use case was bound,
        // detach it while the camera is idle. Capture remains usable with the
        // preview and recorder use cases alone.
        val provider = provider
        if (captureState == CaptureUiState.Idle && provider != null &&
            previewView != null && lifecycleOwner != null
        ) {
            bindUseCases(
                provider,
                bindToken,
                preferThirtyFps = true,
                requestedQuality = requestedQuality,
                includeVision = false
            )
        }
    }

    private fun clearFaceSamples() {
        faceSamples.clear()
        faceSampleSource = null
    }

    private fun takeFaceSamples(source: File): List<FaceSample> {
        if (faceSampleSource != source) return emptyList()
        val samples = faceSamples.toList()
        clearFaceSamples()
        return samples
    }

    private fun persistFaceSamples(
        pending: VideoStore.PendingRecording,
        source: File,
        samples: List<FaceSample>,
        sessionId: String?,
        finalizationBarrier: (suspend (String?) -> Unit)?,
        callback: () -> Unit
    ) {
        finalizeScope.launch {
            try {
                if (samples.isEmpty()) {
                    faceTrackRepository.remove(source)
                } else {
                    faceTrackRepository.write(source, samples)
                }
            } catch (_: Exception) {
                // A track is optional metadata. The raw recording remains
                // complete if sidecar persistence fails.
            }
            if (finalizationBarrier != null) {
                try {
                    finalizationBarrier(sessionId)
                } catch (exception: Exception) {
                    // Metadata drainage is best-effort on an explicit Activity disposal. The
                    // durable capture lifecycle still owns source finalization and recovery.
                    Log.e(TAG, "Unable to drain capture metadata before finalization", exception)
                }
            }
            var coordinatorFailure: Exception? = null
            if (sessionId != null) {
                try {
                    captureCoordinator.finalized(sessionId, source)
                } catch (exception: Exception) {
                    coordinatorFailure = exception
                    Log.e(TAG, "Unable to finalize capture engine session", exception)
                }
            }
            mainExecutor.execute {
                clearEngineSession(pending, sessionId)
                pendingFinalizationBarrier = null
                // Keep the capture blocked until both optional metadata and
                // the durable engine handoff have completed. This prevents a
                // second take from racing the callback below.
                captureState = CaptureUiState.Idle
                recordingStartedAt = 0L
                if (coordinatorFailure != null && !released) {
                    // The raw recording is still valid and is handed to the
                    // review flow below.  Surface the engine failure only as
                    // an existing error hint for callers that can display it.
                    listener?.onRecordingError(context.getString(R.string.error_recording_failed))
                }
                callback()
            }
        }
    }

    private fun clearEngineSession(
        pending: VideoStore.PendingRecording,
        sessionId: String?,
    ) {
        if (sessionId == null || engineSessions[pending] != sessionId) return
        engineSessions.remove(pending)
        clearActiveSession(pending)
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                val pending = pendingRecording
                val sessionId = engineSessionFor(pending)
                if (sessionId != null) {
                    try {
                        captureCoordinator.started(sessionId)
                    } catch (exception: Exception) {
                        Log.e(TAG, "Unable to record capture start", exception)
                    }
                }
                if (captureState == CaptureUiState.Starting && !released) {
                    captureState = CaptureUiState.Recording
                    recordingStartedAt = SystemClock.elapsedRealtime()
                } else if (captureState == CaptureUiState.Finalizing || released) {
                    // Stop can race CameraX's Start event.  CameraX owns the
                    // final callback, so keep the handle and stop it here.
                    recording?.stop()
                }
            }

            is VideoRecordEvent.Finalize -> finalizeRecording(event)
        }
    }

    private fun finalizeRecording(event: VideoRecordEvent.Finalize) {
        val pending = pendingRecording
        val file = pending?.outputFile
        val sessionId = engineSessionFor(pending)
        recording = null

        if (pending == null || file == null) {
            pendingRecording = null
            sessionId?.let { failEngineSession(it, "finalize_without_pending_recording") }
            pending?.let { engineSessions.remove(it) }
            clearActiveSession(pending)
            clearFaceSamples()
            captureState = CaptureUiState.Idle
            recordingStartedAt = 0L
            if (!released) {
                listener?.onRecordingError(context.getString(R.string.error_recording_unplayable))
            }
            return
        }

        val outputExists = file.isFile && file.length() > 0L
        if (event.hasError()) {
            // CameraX can report a finalize error after writing a playable
            // file. Preserve that file instead of deleting valid user data.
            captureState = CaptureUiState.Finalizing
            if (outputExists) {
                val error = event.error
                finalizeScope.launch {
                    val playable = isPlayableVideo(file)
                    mainExecutor.execute {
                        finishErroredFinalize(pending, file, error, playable)
                    }
                }
            } else {
                sessionId?.let { failEngineSession(it, "finalize_error_${event.error}") }
                pendingRecording = null
                engineSessions.remove(pending)
                clearActiveSession(pending)
                clearFaceSamples()
                captureState = CaptureUiState.Idle
                recordingStartedAt = 0L
                pending.discard()
                if (!released) listener?.onRecordingError(recordingErrorMessage(event.error))
            }
        } else if (outputExists) {
            val samples = takeFaceSamples(file)
            pendingRecording = null
            captureState = CaptureUiState.Finalizing
            recordingStartedAt = 0L
            pending.complete()
            persistFaceSamples(pending, file, samples, sessionId, pendingFinalizationBarrier) {
                if (!released) listener?.onRecordingFinalized(file)
            }
        } else {
            sessionId?.let { failEngineSession(it, "finalized_without_playable_output") }
            pendingRecording = null
            engineSessions.remove(pending)
            clearActiveSession(pending)
            clearFaceSamples()
            captureState = CaptureUiState.Idle
            recordingStartedAt = 0L
            pending.discard()
            if (!released) listener?.onRecordingError(context.getString(R.string.error_recording_unplayable))
        }
    }

    private fun finishErroredFinalize(
        pending: VideoStore.PendingRecording,
        file: File,
        error: Int,
        playable: Boolean?
    ) {
        if (pendingRecording !== pending) return

        val sessionId = engineSessionFor(pending)
        pendingRecording = null
        recordingStartedAt = 0L
        when (playable) {
            true -> {
                captureState = CaptureUiState.Finalizing
                val samples = takeFaceSamples(file)
                pending.complete()
                persistFaceSamples(pending, file, samples, sessionId, pendingFinalizationBarrier) {
                    if (!released) listener?.onRecordingFinalized(file)
                }
            }

            false -> {
                captureState = CaptureUiState.Idle
                sessionId?.let { failEngineSession(it, "finalize_error_$error") }
                engineSessions.remove(pending)
                clearActiveSession(pending)
                clearFaceSamples()
                pending.discard()
                if (!released) listener?.onRecordingError(recordingErrorMessage(error))
            }

            null -> {
                captureState = CaptureUiState.Idle
                sessionId?.let { captureCoordinator.interrupted(it, "finalize_validation_unavailable_$error") }
                engineSessions.remove(pending)
                clearActiveSession(pending)
                clearFaceSamples()
                pending.preserveForRecovery()
                if (!released) listener?.onRecordingError(context.getString(R.string.error_recording_failed))
            }
        }
    }

    private fun cameraErrorMessage(exception: Exception): String {
        return if (exception is IllegalArgumentException) {
            context.getString(R.string.error_camera_quality_unsupported)
        } else {
            context.getString(R.string.error_camera_unavailable)
        }
    }

    private fun recordingErrorMessage(error: Int): String {
        val resourceId = when (error) {
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                R.string.error_recording_insufficient_storage
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                R.string.error_recording_file_size_limit
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
                R.string.error_recording_encoding_failed
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                R.string.error_recording_no_valid_data
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                R.string.error_recording_source_inactive
            else -> R.string.error_recording_failed
        }
        return context.getString(resourceId)
    }

    private fun isPlayableVideo(file: File): Boolean? {
        if (!file.isFile || file.length() <= 0L) return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            duration > 0L && width > 0 && height > 0
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Metadata cleanup is best effort.
            }
        }
    }
}
