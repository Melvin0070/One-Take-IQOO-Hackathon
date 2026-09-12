package com.example.one_take.captions

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.onetake.engine.PauseCandidate
import com.example.one_take.audio.SpeechPauseDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Captures bounded mono 16 kHz PCM while a camera recording is in progress.
 *
 * The AudioRecord is created and started synchronously so callers can start it
 * immediately before CameraX begins recording. The blocking read itself runs
 * on the private IO scope. A stop request first unblocks that read and the
 * read job owns the final stop/release sequence, so the AudioRecord is never
 * released while another thread is inside [AudioRecord.read].
 */
internal class LiveMicrophone(
    private val context: Context? = null,
    private val onPauseCandidate: (PauseCandidate) -> Unit = {},
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var stopRequested = false
    private var runningState = false
    private var samples = FloatArray(0)
    private var sampleCountState = 0
    private var failureState: String? = null
    private val pauseCandidatesState = ArrayList<PauseCandidate>()

    /** Starts a fresh capture, returning false when AudioRecord is unavailable. */
    fun start(): Boolean = synchronized(lock) {
        // A previous stop can still be waiting for READ_BLOCKING to return.
        // Do not overlap that cleanup with a new AudioRecord instance.
        if (runningState || readJob?.isActive == true) return@synchronized false

        failureState = null
        stopRequested = false
        sampleCountState = 0
        pauseCandidatesState.clear()

        val minBufferBytes = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (error: Exception) {
            failureState = initializationFailure(error)
            return@synchronized false
        }

        if (minBufferBytes <= 0) {
            failureState = "AudioRecord reported an invalid buffer size"
            return@synchronized false
        }

        val bufferSizeBytes = maxOf(minBufferBytes, READ_BUFFER_BYTES).let { size ->
            // A mono 16-bit frame is two bytes. Keep the builder's size frame-aligned
            // even when a device reports an unusual minimum buffer size.
            size + (size and 1)
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } catch (_: SecurityException) {
            failureState = "Microphone permission is unavailable"
            return@synchronized false
        } catch (error: Exception) {
            failureState = initializationFailure(error)
            return@synchronized false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            releaseQuietly(record)
            failureState = "AudioRecord could not be initialized"
            return@synchronized false
        }

        try {
            record.startRecording()
        } catch (error: Exception) {
            releaseQuietly(record)
            failureState = initializationFailure(error)
            return@synchronized false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stopQuietly(record)
            releaseQuietly(record)
            failureState = "AudioRecord did not enter the recording state"
            return@synchronized false
        }

        samples = FloatArray(MAX_SAMPLES)
        audioRecord = record
        runningState = true
        readJob = try {
            scope.launch {
                readLoop(record)
            }
        } catch (error: Exception) {
            audioRecord = null
            runningState = false
            stopRequested = false
            stopQuietly(record)
            releaseQuietly(record)
            failureState = "Audio capture could not start: ${error.message ?: error::class.java.simpleName}"
            null
        }

        readJob != null
    }

    /** Requests capture stop. The blocking read is released by AudioRecord.stop(). */
    fun stop() {
        val record = synchronized(lock) {
            stopRequested = true
            runningState = false
            audioRecord
        }
        if (record != null) stopQuietly(record)
    }

    /** Suspends until the current read job has stopped and released AudioRecord. */
    suspend fun awaitStopped() {
        val job = synchronized(lock) { readJob }
        job?.join()
    }

    /** Returns a stable copy of all PCM samples captured so far. */
    fun snapshot(): FloatArray = synchronized(lock) {
        samples.copyOf(sampleCountState)
    }

    /** Returns the pause candidates emitted from this capture's PCM stream. */
    fun pauseCandidates(): List<PauseCandidate> = synchronized(lock) {
        pauseCandidatesState.toList()
    }

    val sampleCount: Int
        get() = synchronized(lock) { sampleCountState }

    val failure: String?
        get() = synchronized(lock) { failureState }

    val running: Boolean
        get() = synchronized(lock) { runningState }

    private suspend fun readLoop(record: AudioRecord) {
        val readBuffer = ShortArray(READ_BUFFER_SAMPLES)
        var pauseDetector: SpeechPauseDetector? = null
        var emptyReads = 0
        try {
            val activeDetector = SpeechPauseDetector.create(context).also { pauseDetector = it }
            while (true) {
                val requestSize = synchronized(lock) {
                    if (stopRequested || audioRecord !== record) {
                        0
                    } else {
                        (MAX_SAMPLES - sampleCountState).coerceAtMost(READ_BUFFER_SAMPLES)
                    }
                }
                if (requestSize == 0) break

                val read = record.read(readBuffer, 0, requestSize, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (!isStopRequested()) {
                        setFailure("AudioRecord read failed: $read")
                    }
                    break
                }
                if (read == 0) {
                    // READ_BLOCKING normally waits for at least one frame, but
                    // a transient empty read is legal on some device drivers.
                    if (++emptyReads >= 100) {
                        setFailure("AudioRecord stopped producing samples")
                        break
                    }
                    delay(10)
                    continue
                }

                emptyReads = 0
                val count = read.coerceAtMost(requestSize)
                var reachedLimit = false
                var newSamples = FloatArray(0)
                synchronized(lock) {
                    if (audioRecord === record) {
                        val destination = sampleCountState
                        val writable = (MAX_SAMPLES - destination).coerceAtMost(count)
                        for (index in 0 until writable) {
                            samples[destination + index] = readBuffer[index] / PCM_SCALE
                        }
                        sampleCountState += writable
                        if (writable > 0) {
                            newSamples = samples.copyOfRange(destination, destination + writable)
                        }
                        reachedLimit = sampleCountState == MAX_SAMPLES
                        if (reachedLimit) {
                            failureState = "Audio capture reached the 120-second limit"
                            stopRequested = true
                            runningState = false
                        }
                    }
                }

                // Feed only samples that were accepted into this capture.  The detector and
                // callbacks stay outside the AudioRecord state lock; callback failures are
                // isolated so they cannot terminate raw capture.
                if (newSamples.isNotEmpty()) {
                    val candidates = runCatching { activeDetector.append(newSamples) }
                        .getOrElse { emptyList() }
                    if (candidates.isNotEmpty()) {
                        val newCandidates = synchronized(lock) {
                            candidates.filter { candidate ->
                                pauseCandidatesState.none { it.id == candidate.id }
                            }.also { pauseCandidatesState.addAll(it) }
                        }
                        newCandidates.forEach { candidate ->
                            runCatching { onPauseCandidate(candidate) }
                        }
                    }
                }

                if (reachedLimit) {
                    stopQuietly(record)
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isStopRequested()) {
                setFailure("AudioRecord capture failed: ${error.message ?: error::class.java.simpleName}")
            }
        } finally {
            pauseDetector?.close()
            // This is the only release path. stop() may run concurrently, but
            // it only requests stop and never releases the object itself.
            stopQuietly(record)
            releaseQuietly(record)
            synchronized(lock) {
                if (audioRecord === record) {
                    audioRecord = null
                    runningState = false
                    stopRequested = false
                }
            }
        }
    }

    private fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

    private fun setFailure(message: String) {
        synchronized(lock) {
            if (!stopRequested) {
                failureState = message
                runningState = false
                stopRequested = true
            }
        }
    }

    private fun initializationFailure(error: Exception): String {
        return "AudioRecord initialization failed: ${error.message ?: error::class.java.simpleName}"
    }

    private fun stopQuietly(record: AudioRecord) {
        // Calling stop directly is intentional. A driver may report a stale
        // recordingState while read() is still blocked, and stop() is what
        // unblocks READ_BLOCKING in that case. IllegalStateException is safe
        // to ignore for an already-stopped or failed AudioRecord.
        runCatching { record.stop() }
    }

    private fun releaseQuietly(record: AudioRecord) {
        runCatching { record.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLES = SAMPLE_RATE * 120
        const val READ_BUFFER_SAMPLES = 1_024
        const val READ_BUFFER_BYTES = READ_BUFFER_SAMPLES * 2
        const val PCM_SCALE = 32_768f
    }
}
