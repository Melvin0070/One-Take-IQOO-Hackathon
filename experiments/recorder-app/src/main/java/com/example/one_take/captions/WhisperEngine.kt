package com.example.one_take.captions

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.example.one_take.inference.AppInferenceModel
import com.example.one_take.inference.AppInferenceRuntime
import com.example.one_take.inference.AppInferenceSessions
import com.example.one_take.inference.InferenceDiagnostics
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.InferenceSession
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
internal class WhisperEngine(
    private val policy: BackendPolicy = BackendPolicy.NPU_PREFERRED,
    private val diagnostics: InferenceDiagnostics = AppInferenceRuntime.diagnostics,
) {
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
                currentCoroutineContext().ensureActive()

                // The engine owns model/session preparation.  A strict NPU
                // policy rejects before the CPU preparation callback runs.
                val inferenceSession = AppInferenceSessions.open<WhisperInferenceRequest, Array<CaptionSegment>>(
                        model = AppInferenceModel.WHISPER_TINY,
                        policy = policy,
                        diagnostics = diagnostics,
                    ) { modelSpec ->
                        ensureNativeLoaded()
                        val nativeHandle = createSessionNative(model.absolutePath)
                        require(nativeHandle != 0L) { "Whisper could not create a session" }
                        object : InferenceSession<WhisperInferenceRequest, Array<CaptionSegment>> {
                            override val model = modelSpec
                            override val backend = BackendKind.CPU

                            override fun execute(input: WhisperInferenceRequest): Array<CaptionSegment> =
                                transcribeSessionNative(
                                    nativeHandle,
                                    input.samples,
                                    input.cancellationToken,
                                    input.optimizeForStreaming,
                                )

                            override fun close() = destroySessionNative(nativeHandle)
                        }
                }
                try {
                    val session = PcmSession(optimizeForStreaming, inferenceSession)
                    try {
                        currentCoroutineContext().ensureActive()
                        block(session)
                    } finally {
                        // Cancellation must not prevent the native handle from
                        // being destroyed, including if a child inference is
                        // still unwinding from its native call.
                        withContext(NonCancellable) { session.close() }
                    }
                } catch (failure: Throwable) {
                    // PcmSession closes the engine session after native
                    // ownership has been established.  Before that point
                    // this path still owns the engine session directly.
                    withContext(NonCancellable) { inferenceSession.close() }
                    throw failure
                }
            }
        }

    /**
     * A serialized inference view over one persistent native Whisper context.
     * Samples must be mono, 16 kHz PCM in [-1, 1], and no longer than 120 s.
     */
    inner class PcmSession internal constructor(
        private val optimizeForStreaming: Boolean,
        private val inferenceSession: InferenceSession<WhisperInferenceRequest, Array<CaptionSegment>>,
    ) {
        private val inferenceLock = Mutex()
        private var closed = false

        @OptIn(InternalCoroutinesApi::class)
        suspend fun transcribe(samples: FloatArray): List<CaptionSegment> = withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                check(!closed) { "Whisper session is closed" }
                require(samples.isNotEmpty()) { "audio is empty" }
                require(samples.size <= MAX_SAMPLES) { "audio is longer than 120 seconds" }
                currentCoroutineContext().ensureActive()

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
                    val result = inferenceSession.execute(
                        WhisperInferenceRequest(
                            samples = samples,
                            cancellationToken = cancellationToken,
                            optimizeForStreaming = optimizeForStreaming,
                        ),
                    ).toList()
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
                inferenceSession.close()
            }
        }
    }

    /** Typed input for one synchronous call through the engine-owned session. */
    internal data class WhisperInferenceRequest(
        val samples: FloatArray,
        val cancellationToken: Long,
        val optimizeForStreaming: Boolean,
    )

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
