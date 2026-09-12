package com.example.one_take.audio

import android.content.Context
import com.example.one_take.inference.AppInferenceModel
import com.example.one_take.inference.AppInferenceRuntime
import com.example.one_take.inference.AppInferenceSessions
import com.example.one_take.inference.InferenceDiagnostics
import com.onetake.engine.SpeechActivity
import com.onetake.engine.SpeechFrameClassifier
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.InferenceSession
import com.onetake.engine.inference.InferenceSessionStateException
import com.onetake.engine.inference.ModelSpec
import java.security.MessageDigest

/**
 * Offline Silero VAD backed by the whisper.cpp runtime already in the APK.
 *
 * The classifier owns one stateful native context and serializes every frame
 * call. Frames are 32 ms at 16 kHz, matching the bundled model's input size.
 */
class SileroSpeechClassifier private constructor() : SpeechFrameClassifier, AutoCloseable {
    private var inferenceSession: InferenceSession<SileroInferenceRequest, Int>? = null
    private var closed = false

    override val frameSamples: Int
        get() = FRAME_SAMPLES

    @Synchronized
    override fun classify(samples: FloatArray): SpeechActivity {
        val session = inferenceSession
        if (closed || session == null || !isValidFrame(samples)) {
            return SpeechActivity.UNKNOWN
        }

        return try {
            when (session.execute(SileroInferenceRequest(samples))) {
                1 -> SpeechActivity.SPEECH
                0 -> SpeechActivity.NON_SPEECH
                else -> SpeechActivity.UNKNOWN
            }
        } catch (_: InferenceSessionStateException) {
            // Preserve the classifier's UNKNOWN contract after the engine
            // records a stateful native failure.  The poisoned session is not
            // replayed through CPU or recreated here.
            SpeechActivity.UNKNOWN
        } catch (_: NativeInferenceFailed) {
            SpeechActivity.UNKNOWN
        }
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        val session = inferenceSession
        inferenceSession = null
        session?.close()
    }

    private fun isValidFrame(samples: FloatArray): Boolean {
        if (samples.size != FRAME_SAMPLES) {
            return false
        }
        return samples.all { sample ->
            sample.isFinite() && sample >= -1f && sample <= 1f
        }
    }

    private external fun nativeClassify(handle: Long, samples: FloatArray): Int

    private external fun nativeDestroy(handle: Long)

    private external fun nativeCreate(assetManager: android.content.res.AssetManager, assetName: String): Long

    private data class SileroInferenceRequest(val samples: FloatArray)

    private class NativeInferenceFailed : IllegalStateException("Silero VAD inference failed")

    companion object {
        const val SAMPLE_RATE: Int = 16_000
        const val FRAME_SAMPLES: Int = 512
        const val MODEL_ASSET: String = "ggml-silero-v6.2.0.bin"
        private const val MODEL_SHA256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"

        private val loadLock = Any()
        @Volatile private var modelVerified = false
        @Volatile private var nativeLoaded = false

        /** Creates a fresh model context or throws when the model/runtime is unavailable. */
        @JvmStatic
        fun create(
            context: Context,
            policy: BackendPolicy = BackendPolicy.NPU_PREFERRED,
            diagnostics: InferenceDiagnostics = AppInferenceRuntime.diagnostics,
        ): SileroSpeechClassifier {
            // Construct the receiver before preparation because the existing
            // JNI methods are instance bindings.  The engine's CPU prepare
            // callback creates and owns the native model context; a strict NPU
            // policy therefore rejects before nativeCreate is called.
            val classifier = SileroSpeechClassifier()
            val session = AppInferenceSessions.open<SileroInferenceRequest, Int>(
                model = AppInferenceModel.SILERO_VAD,
                policy = policy,
                diagnostics = diagnostics,
            ) { modelSpec ->
                synchronized(loadLock) {
                    if (!modelVerified) {
                        verifyModel(context)
                        modelVerified = true
                    }
                    if (!nativeLoaded) {
                        try {
                            System.loadLibrary("caption_engine")
                            nativeLoaded = true
                        } catch (error: UnsatisfiedLinkError) {
                            throw IllegalStateException("Offline Silero VAD runtime is unavailable", error)
                        }
                    }
                }
                val nativeHandle = classifier.nativeCreate(context.assets, MODEL_ASSET)
                require(nativeHandle != 0L) { "Unable to load offline Silero VAD model" }
                object : InferenceSession<SileroInferenceRequest, Int> {
                    override val model: ModelSpec = modelSpec
                    override val backend: BackendKind = BackendKind.CPU

                    override fun execute(input: SileroInferenceRequest): Int {
                        val result = classifier.nativeClassify(nativeHandle, input.samples)
                        // -1 is a valid uncertain prediction; -2 indicates a native
                        // computation failure and must retire the stateful session.
                        if (result !in -1..1) throw NativeInferenceFailed()
                        return result
                    }

                    override fun close() = classifier.nativeDestroy(nativeHandle)
                }
            }
            classifier.inferenceSession = session
            return classifier
        }

        private fun verifyModel(context: Context) {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(MODEL_ASSET).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            check(actual == MODEL_SHA256) {
                "Silero VAD model checksum mismatch"
            }
        }
    }
}
