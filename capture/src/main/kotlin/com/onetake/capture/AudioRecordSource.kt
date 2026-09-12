package com.onetake.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.onetake.engine.AudioFrame
import com.onetake.engine.SAMPLE_RATE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One observed audio route and the sample at which it became active. */
internal data class AudioRouteSample(val device: String, val sample: Long)

/**
 * Owns a single VOICE_RECOGNITION AudioRecord and its absolute sample counter.
 *
 * The reader is the only thread that talks to AudioRecord after [start]. It writes each accepted
 * buffer before publishing the immutable copy, so a slow recognizer cannot make the raw WAV
 * shorter than the stream used for inference. [stop] only interrupts the blocking read; release
 * happens in the reader's finally block and never races an in-flight read.
 */
internal class AudioRecordSource(
    private val context: Context,
    private val outputDirectory: File,
    private val bufferSamples: Int,
    private val onRouteChanged: (AudioRouteSample) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private companion object {
        const val MIN_BUFFER_MULTIPLIER = 2
        const val MAX_EMPTY_READS = 100
        const val PCM_BYTES = 2
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val framesState = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val sampleCountState = AtomicLong(0L)
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var wavWriter: PcmWavWriter? = null
    private var wavPath: File? = null
    private var failureState: String? = null
    private var routeCallbackRegistered = false
    private var currentRouteState: String? = null
    private var wasClientSilencedState = false
    private var clientSilenceObservedState = false
    private var signalObservedState = false

    private val routeCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            val route = configs.firstOrNull()?.audioDevice?.let(::describeDevice) ?: "unknown"
            val silenced = configs.firstOrNull()?.isClientSilenced == true
            synchronized(lock) {
                wasClientSilencedState = silenced
                clientSilenceObservedState = configs.isNotEmpty()
            }
            publishRoute(route)
        }
    }

    val frames: Flow<AudioFrame> = framesState.asSharedFlow()

    val sampleCount: Long
        get() = sampleCountState.get()

    val failure: String?
        get() = synchronized(lock) { failureState }

    val currentRoute: String?
        get() = synchronized(lock) { currentRouteState }

    val wasClientSilenced: Boolean
        get() = synchronized(lock) { wasClientSilencedState }

    val clientSilenceObserved: Boolean
        get() = synchronized(lock) { clientSilenceObservedState }

    val hasSignal: Boolean
        get() = synchronized(lock) { signalObservedState }

    val completedWavFile: File?
        get() = synchronized(lock) { wavPath?.takeIf { it.isFile && it.length() > 44L } }

    /**
     * Creates and starts AudioRecord synchronously so CameraX can be started immediately after it.
     * The recording callback is registered before [AudioRecord.startRecording], as required by
     * Android's callback contract.
     */
    fun start(wavFile: File): Boolean = synchronized(lock) {
        if (readJob?.isActive == true || audioRecord != null) return false
        failureState = null
        wasClientSilencedState = false
        clientSilenceObservedState = false
        signalObservedState = false
        currentRouteState = null
        sampleCountState.set(0L)
        stopRequested.set(false)
        wavPath = wavFile

        val requestedSamples = bufferSamples.coerceIn(256, 8_192)
        val minBytes = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (error: Exception) {
            failureState = "AudioRecord buffer query failed: ${error.message ?: error::class.java.simpleName}"
            return false
        }
        if (minBytes <= 0) {
            failureState = "AudioRecord reported an invalid buffer size"
            return false
        }

        val bufferBytes = maxOf(minBytes * MIN_BUFFER_MULTIPLIER, requestedSamples * PCM_BYTES)
            .let { size -> size + (size and 1) }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (_: SecurityException) {
            failureState = "Microphone permission is unavailable"
            return false
        } catch (error: Exception) {
            failureState = "AudioRecord initialization failed: ${error.message ?: error::class.java.simpleName}"
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            failureState = "AudioRecord could not be initialized"
            return false
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager != null) {
            try {
                audioManager.registerAudioRecordingCallback(routeCallback, mainHandler)
                routeCallbackRegistered = true
            } catch (error: Exception) {
                // AudioRecord remains useful without route callbacks; initial routing is still
                // published from the routed device below.
                routeCallbackRegistered = false
            }
        }
        try {
            record.startRecording()
        } catch (error: Exception) {
            unregisterCallback(audioManager)
            record.release()
            failureState = "AudioRecord could not start: ${error.message ?: error::class.java.simpleName}"
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            unregisterCallback(audioManager)
            runCatching { record.stop() }
            record.release()
            failureState = "AudioRecord did not enter the recording state"
            return false
        }

        audioManager?.activeRecordingConfigurations
            ?.firstOrNull()
            ?.let { activeConfiguration ->
                synchronized(lock) {
                    wasClientSilencedState = activeConfiguration.isClientSilenced
                    clientSilenceObservedState = true
                }
            }

        val writer = try {
            outputDirectory.mkdirs()
            PcmWavWriter.open(wavFile, SAMPLE_RATE, channels = 1)
        } catch (error: Exception) {
            unregisterCallback(audioManager)
            runCatching { record.stop() }
            record.release()
            failureState = "WAV file could not be opened: ${error.message ?: error::class.java.simpleName}"
            return false
        }

        audioRecord = record
        wavWriter = writer
        publishRoute(record.routedDevice?.let(::describeDevice) ?: describeInputs(audioManager))
        readJob = scope.launch { readLoop(record, writer, audioManager, requestedSamples) }
        readJob != null
    }

    /** Interrupts a blocking read. The reader closes the writer and AudioRecord. */
    fun stop() {
        stopRequested.set(true)
        val (record, job) = synchronized(lock) { audioRecord to readJob }
        if (record != null) runCatching { record.stop() }
        // A slow SharedFlow collector must not keep finalization waiting after the native read
        // has been interrupted. Cancellation still runs the reader's finally block, which closes
        // the WAV and releases AudioRecord in one place.
        job?.cancel()
    }

    suspend fun awaitStopped() {
        val job = synchronized(lock) { readJob }
        job?.join()
    }

    private suspend fun readLoop(
        record: AudioRecord,
        writer: PcmWavWriter,
        audioManager: AudioManager?,
        readSamples: Int,
    ) {
        val buffer = ShortArray(readSamples)
        var emptyReads = 0
        try {
            while (!stopRequested.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (!stopRequested.get()) setFailure("AudioRecord read failed: $read")
                    break
                }
                if (read == 0) {
                    if (++emptyReads >= MAX_EMPTY_READS) {
                        setFailure("AudioRecord stopped producing samples")
                        break
                    }
                    delay(10L)
                    continue
                }
                emptyReads = 0
                val copy = buffer.copyOf(read)
                val startSample = sampleCountState.getAndAdd(read.toLong())
                writer.write(copy)
                if (copy.any { it != 0.toShort() }) synchronized(lock) { signalObservedState = true }
                framesState.emit(AudioFrame(copy, startSample))
                // Do not gate command/route consumers behind VAD; this callback remains tiny and
                // does not change the audio sample clock.
                if (audioManager != null) {
                    val routed = record.routedDevice?.let(::describeDevice)
                    if (routed != null) publishRoute(routed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!stopRequested.get()) setFailure("AudioRecord capture failed: ${error.message ?: error::class.java.simpleName}")
        } finally {
            runCatching { record.stop() }
            unregisterCallback(audioManager)
            runCatching { writer.close() }
            runCatching { record.release() }
            synchronized(lock) {
                if (audioRecord === record) audioRecord = null
                wavWriter = null
                readJob = null
            }
        }
    }

    private fun publishRoute(route: String) {
        val normalized = route.ifBlank { "unknown" }
        val event = synchronized(lock) {
            if (currentRouteState == normalized) null
            else {
                currentRouteState = normalized
                AudioRouteSample(normalized, sampleCountState.get())
            }
        }
        if (event != null) onRouteChanged(event)
    }

    private fun describeInputs(audioManager: AudioManager?): String {
        return audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull()
            ?.let(::describeDevice)
            ?: "unknown"
    }

    private fun describeDevice(device: AudioDeviceInfo): String {
        return device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: "type-${device.type}"
    }

    private fun setFailure(message: String) {
        synchronized(lock) {
            if (!stopRequested.get()) failureState = message
        }
        stopRequested.set(true)
        onFailure(message)
    }

    private fun unregisterCallback(audioManager: AudioManager?) {
        if (audioManager == null) return
        synchronized(lock) {
            if (!routeCallbackRegistered) return
            routeCallbackRegistered = false
        }
        runCatching { audioManager.unregisterAudioRecordingCallback(routeCallback) }
    }
}
