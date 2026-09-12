package com.onetake.engine.android.whisper

import android.content.Context
import com.onetake.engine.android.QnnGraphBackend
import com.onetake.engine.android.whisper.install.WhisperBundleSpec
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.InferenceEngine
import com.onetake.engine.inference.InferenceReportListener
import com.onetake.engine.inference.InferenceSession
import com.onetake.engine.inference.ModelSpec
import com.onetake.engine.whisper.WhisperAudioFeatures
import com.onetake.engine.whisper.WhisperDecodeOptions
import com.onetake.engine.whisper.WhisperGreedyDecoder
import com.onetake.engine.whisper.WhisperVocabulary
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Strict HTP transcription for the pinned Tiny graph pair; production registration is separate. */
class QnnWhisperTranscriber private constructor(
    private val encoder: InferenceSession<Map<String, ByteArray>, Map<String, ByteArray>>,
    private val decoder: InferenceSession<Map<String, ByteArray>, Map<String, ByteArray>>,
    private val vocabulary: WhisperVocabulary,
) : Closeable {
    data class Segment(val startMs: Long, val endMs: Long, val text: String)
    private var closed = false

    /** Blocking worker-thread API; cancellation is observed between native graph calls. */
    @Synchronized
    fun transcribe(samples: FloatArray, isCancelled: () -> Boolean = { false }): List<Segment> {
        check(!closed) { "Whisper transcriber is closed" }
        require(samples.isNotEmpty() && samples.size <= 480_000) { "Expected up to 30 seconds of mono 16 kHz PCM" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Invalid PCM samples" }
        checkCancelled(isCancelled)
        return transcribeWindow(samples, isCancelled)
    }

    private fun transcribeWindow(samples: FloatArray, isCancelled: () -> Boolean): List<Segment> {
        val features = WhisperAudioFeatures.extract(samples, isCancelled)
        checkCancelled(isCancelled)
        val cross = encoder.execute(mapOf("input_features" to halfBytes(features)))
        check(cross.size == 8 && cross.values.all { it.size == 6 * 64 * 1500 * 2 }) {
            "Unexpected Whisper encoder cache shape"
        }
        checkCancelled(isCancelled)
        val graph = WhisperGraphTokens(decoder, cross, isCancelled)
        val result = WhisperGreedyDecoder(vocabulary, graph::next, isCancelled).decode(
            WhisperDecodeOptions(windowDurationMs = (samples.size * 1000L / 16000).coerceAtLeast(1L)),
        )
        check(!result.exhausted) { "Whisper reached the decoder limit before completing this audio window" }
        check(result.timingComplete) { "Whisper returned text without complete model timestamps" }
        return result.segments.map { Segment(it.startMs, it.endMs, it.text) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try { decoder.close() } catch (error: Throwable) { failure = error }
        try { encoder.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    companion object {
        fun open(context: Context, directory: File, listener: InferenceReportListener? = null): QnnWhisperTranscriber {
            val vocabularyFile = File(directory, "vocab.bin")
            require(vocabularyFile.length() == WhisperBundleSpec.VOCABULARY_SIZE_BYTES) { "Whisper vocabulary size mismatch" }
            val vocabulary = vocabularyFile.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(vocabulary)
                .joinToString("") { "%02x".format(it) }
            require(digest == WhisperBundleSpec.VOCABULARY_SHA256) { "Whisper vocabulary digest mismatch" }
            val parsedVocabulary = vocabulary.inputStream().use(WhisperVocabulary::load)
            val encoder = openGraph(context, directory, "encoder", WhisperBundleSpec.ENCODER_SHA256, listener)
            try {
                val decoder = openGraph(context, directory, "decoder", WhisperBundleSpec.DECODER_SHA256, listener)
                return QnnWhisperTranscriber(encoder, decoder, parsedVocabulary)
            } catch (error: Throwable) {
                try { encoder.close() } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        private fun openGraph(
            context: Context, directory: File, graph: String, hash: String, listener: InferenceReportListener?,
        ): InferenceSession<Map<String, ByteArray>, Map<String, ByteArray>> {
            val model = ModelSpec("qualcomm-whisper-tiny-v061-$graph", setOf(BackendKind.NPU))
            val backend = QnnGraphBackend(context, model, File(directory, "$graph.bin"), hash)
            return InferenceEngine(listOf(backend), defaultPolicy = BackendPolicy.NPU_REQUIRED,
                listener = listener).open(model)
        }
    }
}
