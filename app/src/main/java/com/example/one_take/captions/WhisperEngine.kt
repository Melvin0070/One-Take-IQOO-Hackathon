package com.example.one_take.captions

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline transcription backed by the vendored whisper.cpp CPU runtime.
 *
 * The model is deliberately supplied by the caller. This keeps the APK small
 * and lets the model marketplace install or update a package independently.
 */
internal class WhisperEngine {
    companion object {
        // whisper.cpp contexts own substantial memory and are not safe to use
        // concurrently. Hold this lock for the whole session lifetime, even
        // while the caller is processing the returned PcmSession.
        private val nativeLoaded = AtomicBoolean(false)
        private val transcriptionLock = Mutex()

        private const val MAX_SAMPLES = AudioDecoder.TARGET_SAMPLE_RATE * 120
    }

    /**
     * Decodes one recording before acquiring the process-wide native lock,
     * then transcribes it through a short-lived persistent model session.
     */
    suspend fun transcribe(model: File, video: File): List<CaptionSegment> {
        requireModel(model)
        currentCoroutineContext().ensureActive()
        val audio = AudioDecoder.decodeMono16k(video)
        currentCoroutineContext().ensureActive()
        return withSession(model) { session -> session.transcribe(audio) }
    }

    /**
     * Owns one native Whisper context for the duration of [block].
     * The process-wide lock remains held until [block] returns and the native
     * context has been destroyed.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun <T> withSession(model: File, optimizeForStreaming: Boolean = false, block: suspend (PcmSession) -> T): T =
        transcriptionLock.withLock {
            withContext(Dispatchers.Default) {
                requireModel(model)
                ensureNativeLoaded()
                currentCoroutineContext().ensureActive()

                val nativeHandle = createSessionNative(model.absolutePath)
                require(nativeHandle != 0L) { "Whisper could not create a session" }
                val session = PcmSession(nativeHandle, optimizeForStreaming)
                try {
                    currentCoroutineContext().ensureActive()
                    block(session)
                } finally {
                    // Cancellation must not prevent the native handle from
                    // being destroyed, including if a child inference is
                    // still unwinding from its native call.
                    withContext(NonCancellable) { session.close() }
                }
            }
        }

    /**
     * A serialized inference view over one persistent native Whisper context.
     * Samples must be mono, 16 kHz PCM in [-1, 1], and no longer than 120 s.
     */
    inner class PcmSession internal constructor(handle: Long, private val optimizeForStreaming: Boolean) {
        private val inferenceLock = Mutex()
        private var nativeHandle = handle
        private var closed = false

        @OptIn(InternalCoroutinesApi::class)
        suspend fun transcribe(samples: FloatArray): List<CaptionSegment> = withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                check(!closed) { "Whisper session is closed" }
                require(samples.isNotEmpty()) { "audio is empty" }
                require(samples.size <= MAX_SAMPLES) { "audio is longer than 120 seconds" }
                currentCoroutineContext().ensureActive()

                val handle = nativeHandle
                val cancellationToken = resetCancelNative()
                val job = currentCoroutineContext()[Job]
                    ?: error("Transcription has no coroutine job")
                val cancelHandle = job.invokeOnCompletion(onCancelling = true) {
                    // The token prevents a late callback from cancelling a
                    // subsequent inference after this one has completed.
                    cancelNative(cancellationToken)
                }
                try {
                    currentCoroutineContext().ensureActive()
                    val result = transcribeSessionNative(handle, samples, cancellationToken, optimizeForStreaming).toList()
                    currentCoroutineContext().ensureActive()
                    result
                } finally {
                    cancelHandle.dispose()
                }
            }
        }

        /** Waits for any in-flight inference before releasing the native handle. */
        internal suspend fun close() {
            inferenceLock.withLock {
                if (closed) {
                    return@withLock
                }
                closed = true
                val handle = nativeHandle
                nativeHandle = 0L
                destroySessionNative(handle)
            }
        }
    }

    private fun requireModel(model: File) {
        require(model.isFile && model.canRead()) { "Caption model is missing or unreadable" }
        require(model.length() > 0L) { "Caption model is empty" }
    }

    private fun ensureNativeLoaded() {
        if (nativeLoaded.get()) {
            return
        }
        synchronized(nativeLoaded) {
            if (nativeLoaded.get()) {
                return
            }
            try {
                System.loadLibrary("caption_engine")
                nativeLoaded.set(true)
            } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException("Offline caption engine is unavailable", error)
            }
        }
    }

    private external fun createSessionNative(modelPath: String): Long

    private external fun destroySessionNative(handle: Long)

    private external fun transcribeSessionNative(
        handle: Long,
        audio: FloatArray,
        cancellationToken: Long,
        optimizeForStreaming: Boolean,
    ): Array<CaptionSegment>

    private external fun cancelNative(cancellationToken: Long)

    private external fun resetCancelNative(): Long
}
