package com.example.one_take.audio

import android.content.Context
import com.onetake.engine.SpeechActivity
import com.onetake.engine.SpeechFrameClassifier
import java.security.MessageDigest

/**
 * Offline Silero VAD backed by the whisper.cpp runtime already in the APK.
 *
 * The classifier owns one stateful native context and serializes every frame
 * call. Frames are 32 ms at 16 kHz, matching the bundled model's input size.
 */
class SileroSpeechClassifier private constructor(
    nativeHandle: Long,
) : SpeechFrameClassifier, AutoCloseable {
    private var handle = nativeHandle

    override val frameSamples: Int
        get() = FRAME_SAMPLES

    @Synchronized
    override fun classify(samples: FloatArray): SpeechActivity {
        val currentHandle = handle
        if (currentHandle == 0L || !isValidFrame(samples)) {
            return SpeechActivity.UNKNOWN
        }

        return when (nativeClassify(currentHandle, samples)) {
            1 -> SpeechActivity.SPEECH
            0 -> SpeechActivity.NON_SPEECH
            else -> SpeechActivity.UNKNOWN
        }
    }

    @Synchronized
    override fun close() {
        val currentHandle = handle
        if (currentHandle == 0L) {
            return
        }
        handle = 0L
        nativeDestroy(currentHandle)
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
        fun create(context: Context): SileroSpeechClassifier {
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

            val classifier = SileroSpeechClassifier(0L)
            val handle = classifier.nativeCreate(context.assets, MODEL_ASSET)
            if (handle == 0L) {
                throw IllegalStateException("Unable to load offline Silero VAD model")
            }
            classifier.handle = handle
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
