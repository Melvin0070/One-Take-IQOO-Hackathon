package com.example.one_take.audio

import com.onetake.engine.SpeechActivity
import com.onetake.engine.SpeechFrameClassifier

/**
 * Small offline 16 kHz voice activity classifier backed by WebRTC VAD.
 *
 * One instance owns one native VAD state. The serialized boundary is
 * intentional because WebRTC VAD instances are stateful and are not shared
 * between concurrent frame calls.
 */
class WebRtcSpeechClassifier private constructor(
    nativeHandle: Long,
) : SpeechFrameClassifier, AutoCloseable {
    private var handle = nativeHandle

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

    private external fun nativeCreate(mode: Int, sampleRate: Int): Long

    companion object {
        const val SAMPLE_RATE: Int = 16_000
        const val FRAME_SAMPLES: Int = 320
        private const val MODE: Int = 0

        /** Creates a fresh classifier or throws when the native library is unavailable. */
        @JvmStatic
        fun create(): WebRtcSpeechClassifier {
            try {
                System.loadLibrary("speech_vad")
            } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException("Offline speech classifier is unavailable", error)
            }

            val classifier = WebRtcSpeechClassifier(0L)
            val handle = classifier.nativeCreate(MODE, SAMPLE_RATE)
            if (handle == 0L) {
                throw IllegalStateException("Unable to create offline speech classifier")
            }
            classifier.handle = handle
            return classifier
        }
    }
}
