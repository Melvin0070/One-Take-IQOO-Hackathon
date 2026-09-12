package com.example.one_take.audio

import android.content.Context
import com.onetake.engine.SpeechFrameClassifier
import com.onetake.engine.PauseCandidate
import com.onetake.engine.StreamingPauseDetector

/** Owns one speech classifier per PCM timeline; failure falls back to near-silence only. */
internal class SpeechPauseDetector private constructor(
    private val classifier: SpeechFrameClassifier?,
) : AutoCloseable {
    private val detector = classifier?.let { StreamingPauseDetector(it) } ?: StreamingPauseDetector()

    fun append(samples: FloatArray): List<PauseCandidate> = detector.append(samples)

    override fun close() { (classifier as? AutoCloseable)?.close() }

    companion object {
        fun create(context: Context? = null): SpeechPauseDetector {
            if (context != null) {
                val neural = try {
                    SileroSpeechClassifier.create(context.applicationContext)
                } catch (_: LinkageError) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (neural != null) return SpeechPauseDetector(neural)
            }
            val classifier = try {
                WebRtcSpeechClassifier.create()
            } catch (_: LinkageError) {
                null
            } catch (_: Exception) {
                null
            }
            return SpeechPauseDetector(classifier)
        }
    }
}
