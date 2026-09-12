package com.onetake.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.AudioStats
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.onetake.engine.AudioFrame
import com.onetake.engine.VideoAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Camera and microphone capture for one recording session.
 *
 * The app owns the lifecycle and supplies a visible [LifecycleOwner] and [PreviewView] through
 * [bindPreview]. [start] is deliberately separate from binding so the app can persist its
 * session request before the camera encoder opens. CameraX's audio track is the primary cut
 * source; the independent 16 kHz stream is always written to WAV and emitted as [AudioFrame]
 * values for the engine.
 */
class CaptureSession(
    private val context: Context,
    private val config: CaptureConfig = CaptureConfig(),
) : AutoCloseable {

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsState = MutableSharedFlow<CaptureEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val stateState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    private val anchorState = MutableSharedFlow<VideoAnchor>(replay = 1, extraBufferCapacity = 1)
    private val audioSource = AudioRecordSource(
        context = context,
        outputDirectory = config.outputDirectory(context),
        bufferSamples = config.audioBufferSamples,
        onRouteChanged = { route ->
            eventsState.tryEmit(CaptureEvent.AudioRouteChanged(route.device, route.sample))
        },
        onFailure = { message ->
            val sessionId = activeSessionId
            eventsState.tryEmit(CaptureEvent.Failed(message, sessionId))
            mainExecutor.execute { recording?.stop() }
        },
    )

    private var provider: ProcessCameraProvider? = null
    private var providerRequested = false
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var boundCameraId: Int? = null
    private var recording: Recording? = null
    private var pendingFile: File? = null
    private var finalFile: File? = null
    private var wavFile: File? = null
    private var activeSessionId: String? = null
    private var activeAnchor: VideoAnchor? = null
    private var stopRequested = false
    private var truncated = false
    private var closed = false
    private var finalizeJob: kotlinx.coroutines.Job? = null
    private var storageGuard: StorageGuard? = null

    /** Every PCM buffer captured by the independent recognizer microphone. */
    val audio: Flow<AudioFrame> = audioSource.frames

    /** Exactly one anchor is emitted after CameraX reports its recording Start event. */
    val anchor: SharedFlow<VideoAnchor> = anchorState.asSharedFlow()

    /** Lifecycle and recording events for the app coordinator. */
    val events: SharedFlow<CaptureEvent> = eventsState.asSharedFlow()

    /** State required to drive the record control and the preparing indicator. */
    val state: StateFlow<CaptureState> = stateState.asStateFlow()

    /** Current number of captured microphone samples, useful when stamping engine actions. */
    val sampleCount: Long
        get() = audioSource.sampleCount

    /** Whether the most recent AudioRecord configuration was client-silenced. */
    val audioClientSilenced: Boolean
        get() = audioSource.wasClientSilenced

    /** Whether Android exposed an active recording configuration to inspect. */
    val audioClientSilenceObserved: Boolean
        get() = audioSource.clientSilenceObserved

    /** True after AudioRecord has observed a non-zero PCM16 sample. */
    val audioHasSignal: Boolean
        get() = audioSource.hasSignal

    /** The last route observed by AudioRecord, or null before the microphone starts. */
    val audioRoute: String?
        get() = audioSource.currentRoute

    /** Device-derived 1080p estimate, or zero when the camera id is not exposed as an integer. */
    val estimatedRecordableMinutes: Int
        get() = storageGuard?.estimatedRecordableMinutes() ?: 0

    /**
     * Binds the preview and recorder to a visible lifecycle.
     *
     * The callback is asynchronous because ProcessCameraProvider is loaded lazily. The app can
     * observe [state] for [CaptureState.Ready] before enabling Record.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        check(!closed) { "CaptureSession is closed" }
        if (stateState.value !is CaptureState.Idle && stateState.value !is CaptureState.Ready) return
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        stateState.value = CaptureState.Binding

        provider?.let { bindUseCases(it) }
        if (providerRequested) return
        providerRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                provider = providerFuture.get()
                bindUseCases(provider ?: return@addListener)
            } catch (error: Exception) {
                providerRequested = false
                fail("Camera provider unavailable: ${error.message ?: error::class.java.simpleName}")
            }
        }, mainExecutor)
    }

    /**
     * Starts CameraX and the independent [android.media.AudioRecord] stream.
     *
     * The call must happen while the supplied lifecycle is visible. Android 14 rejects camera
     * and microphone foreground service starts from the background before a useful fallback can
     * run, so the foreground notification is requested here rather than from a worker.
     */
    fun start(sessionId: String): Boolean {
        if (closed || stateState.value !is CaptureState.Ready) return false
        if (sessionId.isBlank() || recording != null || activeSessionId != null) return false
        val capture = videoCapture ?: return false
        val owner = lifecycleOwner ?: return false
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return false

        val directory = try {
            config.outputDirectory(context).also { it.mkdirs() }
        } catch (_: SecurityException) {
            return false
        }
        if (!directory.isDirectory) {
            fail("Capture directory is unavailable")
            return false
        }

        val token = UUID.randomUUID().toString()
        val pending = File(directory, "$token.mp4.part")
        val output = File(directory, "$token.mp4")
        val wav = File(directory, "$token.wav")
        pendingFile = pending
        finalFile = output
        wavFile = wav
        activeSessionId = sessionId
        activeAnchor = null
        stopRequested = false
        truncated = false
        storageGuard = StorageGuard.forCamera(
            directory = directory,
            cameraId = boundCameraId,
            reserveBytes = config.storageReserveBytes,
        )
        stateState.value = CaptureState.Starting(sessionId)

        if (config.startForegroundService) {
            try {
                CaptureForegroundService.start(context, sessionId)
            } catch (error: SecurityException) {
                failStart("Capture foreground service could not start: ${error.message ?: "permission denied"}")
                return false
            }
        }

        if (config.audioFirst && !audioSource.start(wav)) {
            failStart(audioSource.failure ?: "Microphone unavailable")
            return false
        }

        var microphoneFailedAfterCameraStart = false
        try {
            val outputOptions = FileOutputOptions.Builder(pending).build()
            val prepared = capture.output.prepareRecording(context, outputOptions).withAudioEnabled()
            recording = prepared.start(mainExecutor, ::handleRecordEvent)
            if (!config.audioFirst && !audioSource.start(wav)) {
                microphoneFailedAfterCameraStart = true
                stopRequested = true
                stateState.value = CaptureState.Finalizing(sessionId)
                eventsState.tryEmit(CaptureEvent.Failed(audioSource.failure ?: "Microphone unavailable", sessionId))
                recording?.stop()
            }
        } catch (error: SecurityException) {
            failStart("Camera or microphone permission is unavailable")
            return false
        } catch (error: Exception) {
            failStart("Camera recording could not start: ${error.message ?: error::class.java.simpleName}")
            return false
        }
        if (!microphoneFailedAfterCameraStart) eventsState.tryEmit(CaptureEvent.Started(sessionId))
        return true
    }

    /** Requests a graceful stop. Finalized files are reported through [events]. */
    fun stop() {
        if (closed) return
        val sessionId = activeSessionId ?: return
        if (stateState.value !is CaptureState.Starting && stateState.value !is CaptureState.Recording) return
        stopRequested = true
        stateState.value = CaptureState.Finalizing(sessionId)
        audioSource.stop()
        recording?.stop()
    }

    /** Stops capture, unbinds CameraX and cancels internal scopes. Raw files are retained. */
    override fun close() {
        if (closed) return
        closed = true
        stopRequested = true
        audioSource.stop()
        recording?.stop()
        provider?.unbindAll()
        videoCapture = null
        boundCameraId = null
        lifecycleOwner = null
        previewView = null
        CaptureForegroundService.stop(context)
        ioScope.cancel()
        // A stopped recording may still have a valid .part file while CameraX drains its
        // encoder. Keep all paths on disk so recovery can inspect the raw session.
        if (recording == null) clearSessionReferences()
        stateState.value = CaptureState.Closed
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindUseCases(provider: ProcessCameraProvider) {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        try {
            provider.unbindAll()
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }
            val selector = if (config.lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(config.quality, Quality.FHD, Quality.HD, Quality.SD).distinct(),
            )
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            val video = VideoCapture.Builder(recorder)
                .setTargetRotation(rotation)
                .build()
            val boundCamera = provider.bindToLifecycle(owner, selector, preview, video)
            videoCapture = video
            boundCameraId = Camera2CameraInfo.from(boundCamera.cameraInfo).cameraId.toIntOrNull()
            stateState.value = CaptureState.Ready
            eventsState.tryEmit(CaptureEvent.CameraReady)
        } catch (error: Exception) {
            videoCapture = null
            boundCameraId = null
            fail("Camera binding failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                val sessionId = activeSessionId ?: return
                if (activeAnchor == null) {
                    val anchor = VideoAnchor(
                        sample = audioSource.sampleCount,
                        videoPtsNanos = SystemClock.elapsedRealtimeNanos(),
                        sessionId = sessionId,
                    )
                    activeAnchor = anchor
                    anchorState.tryEmit(anchor)
                    eventsState.tryEmit(CaptureEvent.VideoStarted(anchor))
                }
                if (stopRequested || closed) recording?.stop()
                else stateState.value = CaptureState.Recording(sessionId)
            }

            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val audioStats = stats.audioStats
                val audioActive = audioStats.audioState == AudioStats.AUDIO_STATE_ACTIVE
                val bytes = stats.numBytesRecorded
                val shouldTruncate = storageGuard?.shouldStop(bytes) == true
                if (shouldTruncate && !truncated) {
                    truncated = true
                    eventsState.tryEmit(CaptureEvent.StorageLimitReached(bytes))
                    recording?.stop()
                }
                eventsState.tryEmit(
                    CaptureEvent.Status(
                        bytesRecorded = bytes,
                        cameraAudioActive = audioActive,
                        cameraAudioAmplitude = audioStats.audioAmplitude,
                        sampleCount = audioSource.sampleCount,
                    ),
                )
            }

            is VideoRecordEvent.Finalize -> finalizeRecording(event)
        }
    }

    private fun finalizeRecording(event: VideoRecordEvent.Finalize) {
        if (finalizeJob?.isActive == true) return
        val sessionId = activeSessionId ?: return
        val pending = pendingFile ?: return
        stateState.value = CaptureState.Finalizing(sessionId)
        finalizeJob = ioScope.launch {
            audioSource.stop()
            audioSource.awaitStopped()
            val wav = audioSource.completedWavFile ?: wavFile
            val outputExists = pending.isFile && pending.length() > 0L
            val hasCameraError = event.hasError()
            val playableCandidate = outputExists &&
                (!hasCameraError || event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ||
                    event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
            val output = if (playableCandidate) finishVideoFile(pending, finalFile) else null
            val finalError = if (hasCameraError) event.error else null
            withContext(Dispatchers.Main) {
                recording = null
                CaptureForegroundService.stop(context)
                val anchor = activeAnchor
                if (output != null && anchor != null) {
                    eventsState.tryEmit(
                        CaptureEvent.Finalized(
                            sessionId = sessionId,
                            videoFile = output,
                            wavFile = wav,
                            anchor = anchor,
                            durationSamples = audioSource.sampleCount,
                            truncated = truncated || finalError != null,
                            cameraError = finalError,
                        ),
                    )
                    stateState.value = CaptureState.Ready
                } else {
                    eventsState.tryEmit(
                        CaptureEvent.Failed(
                            "Camera finalize failed${finalError?.let { " ($it)" } ?: ""}",
                            sessionId = sessionId,
                            retainedFile = if (outputExists) pending else null,
                        ),
                    )
                    stateState.value = CaptureState.Ready
                }
                clearSessionReferences()
            }
        }
    }

    private fun finishVideoFile(pending: File, destination: File?): File? {
        if (destination == null) return pending
        if (destination.exists() && !destination.delete()) return pending
        return if (pending.renameTo(destination)) destination else pending
    }

    private fun failStart(message: String) {
        audioSource.stop()
        recording?.stop()
        clearSessionFiles(deletePending = true)
        fail(message)
        CaptureForegroundService.stop(context)
    }

    private fun fail(message: String) {
        stateState.value = CaptureState.Failed(message)
        eventsState.tryEmit(CaptureEvent.Failed(message))
    }

    private fun clearSessionFiles(deletePending: Boolean) {
        if (deletePending) pendingFile?.delete()
        wavFile?.delete()
        clearSessionReferences()
    }

    private fun clearSessionReferences() {
        pendingFile = null
        finalFile = null
        wavFile = null
        activeSessionId = null
        activeAnchor = null
        truncated = false
        storageGuard = null
        stopRequested = false
        finalizeJob = null
    }
}

/** Runtime choices that affect capture without changing the engine contract. */
data class CaptureConfig(
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val quality: Quality = Quality.FHD,
    val audioFirst: Boolean = true,
    val audioBufferSamples: Int = 1_024,
    val storageReserveBytes: Long = 256L * 1024L * 1024L,
    val startForegroundService: Boolean = true,
    val outputDirectory: ((Context) -> File)? = null,
) {
    fun outputDirectory(context: Context): File =
        outputDirectory?.invoke(context) ?: File(context.filesDir, "captures")
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Binding : CaptureState
    data object Ready : CaptureState
    data class Starting(val sessionId: String) : CaptureState
    data class Recording(val sessionId: String) : CaptureState
    data class Finalizing(val sessionId: String) : CaptureState
    data class Failed(val message: String) : CaptureState
    data object Closed : CaptureState
}

sealed interface CaptureEvent {
    data object CameraReady : CaptureEvent
    data class Started(val sessionId: String) : CaptureEvent
    data class VideoStarted(val anchor: VideoAnchor) : CaptureEvent
    data class Status(
        val bytesRecorded: Long,
        val cameraAudioActive: Boolean,
        val cameraAudioAmplitude: Double,
        val sampleCount: Long,
    ) : CaptureEvent
    data class StorageLimitReached(val bytesRecorded: Long) : CaptureEvent
    data class AudioRouteChanged(val device: String, val sample: Long) : CaptureEvent
    data class Finalized(
        val sessionId: String,
        val videoFile: File,
        val wavFile: File?,
        val anchor: VideoAnchor,
        val durationSamples: Long,
        val truncated: Boolean,
        val cameraError: Int?,
    ) : CaptureEvent
    data class Failed(
        val message: String,
        val sessionId: String? = null,
        val retainedFile: File? = null,
    ) : CaptureEvent
}

/**
 * Concurrent capture smoke test entry points.
 *
 * The no-context overload remains for source compatibility with the scaffold and intentionally
 * fails loudly. Instrumentation calls [run] with a visible lifecycle and preview so this test can
 * exercise the real CameraX recorder and AudioRecord instead of a fake.
 */
object ConcurrentCaptureProbe {
    data class Result(
        val cameraXAudioActive: Boolean,
        val audioRecordHasSignal: Boolean,
        val notClientSilenced: Boolean,
        val startOrder: String,
        val notes: String,
        val durationMillis: Long = 0L,
        val audioSamples: Long = 0L,
        val videoFile: File? = null,
    ) {
        val architectureBHolds: Boolean
            get() = cameraXAudioActive && audioRecordHasSignal && notClientSilenced
    }

    @Suppress("UNUSED_PARAMETER")
    fun run(cameraFirst: Boolean): Result =
        throw IllegalStateException("ConcurrentCaptureProbe.run requires Context, LifecycleOwner and PreviewView")

    suspend fun run(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraFirst: Boolean,
        durationMillis: Long = 10_000L,
    ): Result {
        require(durationMillis > 0L) { "durationMillis must be positive" }
        val session = CaptureSession(
            context,
            CaptureConfig(audioFirst = !cameraFirst),
        )
        var cameraAudioActive = false
        var audioRecordHasSignal = false
        var finalFile: File? = null
        val collectScope = CoroutineScope(Dispatchers.Main.immediate)
        val collectJob = collectScope.launch {
            session.events.collect { event ->
                when (event) {
                    is CaptureEvent.Status -> {
                        cameraAudioActive = cameraAudioActive ||
                            (event.cameraAudioActive && event.cameraAudioAmplitude > 0.0)
                    }
                    is CaptureEvent.Finalized -> finalFile = event.videoFile
                    else -> Unit
                }
            }
        }
        session.bindPreview(lifecycleOwner, previewView)
        withContext(Dispatchers.Main) {
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (session.state.value !is CaptureState.Ready && SystemClock.uptimeMillis() < deadline) {
                delay(20L)
            }
        }
        val started = session.start("probe-${UUID.randomUUID()}")
        val audioJob = if (started) {
            CoroutineScope(Dispatchers.Default).launch {
                session.audio.collect { frame ->
                    if (frame.pcm.any { it != 0.toShort() }) audioRecordHasSignal = true
                }
            }
        } else {
            null
        }
        if (started) {
            delay(durationMillis)
            session.stop()
            withContext(Dispatchers.Main) {
                val deadline = SystemClock.uptimeMillis() + 15_000L
                while (session.state.value !is CaptureState.Ready && SystemClock.uptimeMillis() < deadline) {
                    delay(20L)
                }
            }
        }
        audioJob?.cancel()
        val result = Result(
            cameraXAudioActive = cameraAudioActive,
            audioRecordHasSignal = audioRecordHasSignal || session.audioHasSignal,
            notClientSilenced = session.audioClientSilenceObserved && !session.audioClientSilenced,
            startOrder = if (cameraFirst) "camera-first" else "audio-first",
            notes = if (started) "Completed real CameraX and AudioRecord run" else "Capture did not start",
            durationMillis = durationMillis,
            audioSamples = session.sampleCount,
            videoFile = finalFile,
        )
        collectJob.cancel()
        collectScope.cancel()
        session.close()
        return result
    }
}

/**
 * Storage guard used by [CaptureSession]. The bitrate is sourced from the device's
 * [android.media.CamcorderProfile] when a numeric Camera2 id is available; free space remains a
 * hard stop even when a vendor exposes a non-numeric id.
 */
class StorageGuard(
    private val directory: File,
    private val videoBitRateBitsPerSecond: Long? = null,
    private val reserveBytes: Long = 256L * 1024L * 1024L,
) {
    @Suppress("UsableSpace")
    fun estimatedRecordableMinutes(): Int {
        val bitRate = videoBitRateBitsPerSecond ?: return 0
        if (bitRate <= 0L) return 0
        val available = (directory.usableSpace - reserveBytes).coerceAtLeast(0L)
        return ((available * 8L) / bitRate / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Suppress("UsableSpace")
    fun shouldStop(bytesRecorded: Long): Boolean {
        if (bytesRecorded < 0L) return false
        val available = directory.usableSpace
        return available <= reserveBytes || bytesRecorded >= (available - reserveBytes).coerceAtLeast(0L)
    }

    companion object {
        fun forCamera(directory: File, cameraId: Int?, reserveBytes: Long): StorageGuard {
            val bitRate = cameraId?.let { id ->
                runCatching {
                    if (android.media.CamcorderProfile.hasProfile(id, android.media.CamcorderProfile.QUALITY_1080P)) {
                        android.media.CamcorderProfile.get(id, android.media.CamcorderProfile.QUALITY_1080P).videoBitRate.toLong()
                    } else {
                        null
                    }
                }.getOrNull()
            }
            return StorageGuard(directory, bitRate, reserveBytes)
        }
    }
}
