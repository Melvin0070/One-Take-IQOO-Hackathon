package com.onetake.npu

import android.content.Context
import com.onetake.npu.whisper.WhisperAudioFeatures
import com.onetake.npu.whisper.WhisperDecodeOptions
import com.onetake.npu.whisper.WhisperGreedyDecoder
import com.onetake.npu.whisper.WhisperQnnGraphTokens
import com.onetake.npu.whisper.WhisperVocabulary
import com.onetake.npu.whisper.install.WhisperBundleInstaller
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Identifies the processor that actually executed the graph pair. */
enum class NpuProcessor {
    NPU,
}

/** A caption interval expressed in the engine's 16 kHz sample clock. */
data class NpuCaptionSegment(
    val startSample: Long,
    val endSample: Long,
    val text: String,
) {
    init {
        require(startSample >= 0L) { "Caption start sample must be non-negative" }
        require(endSample > startSample) { "Caption end sample must be after its start" }
        require(text.isNotBlank()) { "Caption text must not be blank" }
    }
}

/** Evidence for one strict NPU caption call. No CPU fallback is hidden here. */
data class NpuCaptionDiagnostics(
    val modelId: String,
    val processor: NpuProcessor,
    val encoderCalls: Int,
    val decoderCalls: Int,
    val elapsedMicros: Long,
) {
    init {
        require(modelId.isNotBlank()) { "Model id must not be blank" }
        require(encoderCalls >= 0) { "Encoder call count must be non-negative" }
        require(decoderCalls >= 0) { "Decoder call count must be non-negative" }
        require(elapsedMicros >= 0L) { "Elapsed time must be non-negative" }
    }
}

/** Result returned to the product layer after one verified Whisper window. */
data class NpuCaptionResult(
    val segments: List<NpuCaptionSegment>,
    val diagnostics: NpuCaptionDiagnostics,
) {
    init {
        require(segments.zipWithNext().all { (left, right) -> right.startSample >= left.startSample }) {
            "Caption segments must be ordered by sample index"
        }
    }
}

/**
 * Production-facing Tiny Whisper adapter for the iQOO 15.
 *
 * The two per-chipset QNN context binaries are opened through [QnnGraphSession],
 * which accepts only the SM8850 HTP target and reports graph execution failures.
 * Audio is intentionally bounded to one 30-second window until overlap and
 * deduplication are specified; callers receive an error instead of a truncated
 * caption for a longer recording.
 */
class NpuWhisperTranscriber private constructor(
    private val encoder: QnnGraphSession,
    private val decoder: QnnGraphSession,
    private val vocabulary: WhisperVocabulary,
) : Closeable {
    private var closed = false

    /** Transcribes normalized mono 16 kHz PCM samples at [startSample]. */
    @Synchronized
    fun transcribe(
        samples: FloatArray,
        startSample: Long = 0L,
        language: String? = null,
        isCancelled: () -> Boolean = { false },
    ): NpuCaptionResult {
        check(!closed) { "Whisper transcriber is closed" }
        require(startSample >= 0L) { "Caption start sample must be non-negative" }
        require(samples.isNotEmpty() && samples.size <= WhisperAudioFeatures.MAX_AUDIO_SAMPLES) {
            "Expected between 1 sample and 30 seconds of mono 16 kHz PCM"
        }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Invalid PCM samples" }
        checkCancelled(isCancelled)

        val startedAt = System.nanoTime()
        val features = WhisperAudioFeatures.extract(samples, isCancelled)
        checkCancelled(isCancelled)
        val crossCache = executeEncoder(features)
        checkCancelled(isCancelled)
        val graphTokens = WhisperQnnGraphTokens(decoder, crossCache, isCancelled)
        val decoded = WhisperGreedyDecoder(vocabulary, graphTokens::next, isCancelled).decode(
            WhisperDecodeOptions(
                language = language,
                windowDurationMs = (samples.size * 1000L / WhisperAudioFeatures.SAMPLE_RATE)
                    .coerceAtLeast(1L),
            ),
        )
        check(!decoded.exhausted) {
            "Whisper reached the decoder limit before completing this audio window"
        }
        check(decoded.timingComplete) { "Whisper returned text without complete model timestamps" }
        val elapsedMicros = ((System.nanoTime() - startedAt) / 1_000L).coerceAtLeast(0L)
        return NpuCaptionResult(
            segments = decoded.segments.map { segment ->
                NpuCaptionSegment(
                    startSample = startSample + segment.startMs * WhisperAudioFeatures.SAMPLE_RATE / 1000L,
                    endSample = startSample + segment.endMs * WhisperAudioFeatures.SAMPLE_RATE / 1000L,
                    text = segment.text,
                )
            },
            diagnostics = NpuCaptionDiagnostics(
                modelId = TinyWhisperBundleSpec.MODEL_ID,
                processor = NpuProcessor.NPU,
                encoderCalls = 1,
                decoderCalls = graphTokens.callCount,
                elapsedMicros = elapsedMicros,
            ),
        )
    }

    /** Transcribes signed 16-bit little-endian PCM samples at [startSample]. */
    fun transcribePcm(
        samples: ShortArray,
        startSample: Long = 0L,
        language: String? = null,
        isCancelled: () -> Boolean = { false },
    ): NpuCaptionResult {
        require(samples.isNotEmpty() && samples.size <= WhisperAudioFeatures.MAX_AUDIO_SAMPLES) {
            "Expected between 1 sample and 30 seconds of mono 16 kHz PCM"
        }
        return transcribe(
            samples = FloatArray(samples.size) { index -> samples[index] / 32768.0f },
            startSample = startSample,
            language = language,
            isCancelled = isCancelled,
        )
    }

    /** Transcribes a raw signed 16-bit little-endian mono 16 kHz PCM file. */
    fun transcribeFile(
        file: File,
        startSample: Long = 0L,
        language: String? = null,
        isCancelled: () -> Boolean = { false },
    ): NpuCaptionResult {
        require(file.isFile && file.canRead()) { "PCM file is missing or unreadable: ${file.path}" }
        require(file.length() in 2L..(WhisperAudioFeatures.MAX_AUDIO_SAMPLES * 2L)) {
            "Expected a non-empty PCM file of at most 30 seconds"
        }
        val bytes = file.readBytes()
        require(bytes.size % 2 == 0) { "PCM file must contain complete 16-bit samples" }
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return transcribePcm(samples, startSample, language, isCancelled)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            decoder.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            encoder.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun executeEncoder(features: FloatArray): Map<String, ByteArray> {
        val inputs = encoder.inputs
        require(inputs.size == 1 && inputs[0].name == "input_features") {
            "Whisper encoder graph has an unexpected input contract"
        }
        val output = encoder.execute(listOf(com.onetake.npu.whisper.halfBytes(features)))
        require(output.size == 8 && output.all { it.size == 6 * 64 * 1500 * 2 }) {
            "Unexpected Whisper encoder cache shape"
        }
        return encoder.outputs.mapIndexed { index, spec -> spec.name to output[index] }.toMap()
    }

    companion object {
        /** Opens a verified bundle directory without installing or modifying it. */
        @JvmStatic
        fun open(context: Context, directory: File): NpuWhisperTranscriber {
            require(directory.isDirectory) { "Whisper bundle directory is unavailable: ${directory.path}" }
            validateBundle(directory)
            val vocabulary = TinyWhisperBundleSpec.file(directory, TinyWhisperBundleSpec.VOCABULARY_NAME)
                .inputStream().use(WhisperVocabulary::load)
            val encoder = QnnGraphBackend(
                context = context,
                binary = TinyWhisperBundleSpec.file(directory, TinyWhisperBundleSpec.ENCODER_NAME),
                sha256 = TinyWhisperBundleSpec.ENCODER_SHA256,
            ).open("encoder")
            return try {
                val decoder = QnnGraphBackend(
                    context = context,
                    binary = TinyWhisperBundleSpec.file(directory, TinyWhisperBundleSpec.DECODER_NAME),
                    sha256 = TinyWhisperBundleSpec.DECODER_SHA256,
                ).open("decoder")
                NpuWhisperTranscriber(encoder, decoder, vocabulary)
            } catch (error: Throwable) {
                try {
                    encoder.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        /** Opens the active bundle selected by [WhisperBundleInstaller]. */
        @JvmStatic
        fun openInstalled(context: Context): NpuWhisperTranscriber {
            val directory = WhisperBundleInstaller.resolveInstalled(context)
                ?: error("No verified Tiny Whisper bundle is installed")
            return open(context, directory)
        }

        /** Installs a caller-selected local archive, then opens its verified active bundle. */
        @JvmStatic
        fun installAndOpen(
            context: Context,
            archive: File,
            cancellation: com.onetake.npu.whisper.install.WhisperInstallCancellation =
                com.onetake.npu.whisper.install.WhisperInstallCancellation.NONE,
        ): NpuWhisperTranscriber {
            WhisperBundleInstaller(context).install(archive, cancellation)
            return openInstalled(context)
        }

        private fun validateBundle(directory: File) {
            val artifacts = listOf(
                TinyWhisperBundleSpec.ENCODER_NAME to TinyWhisperBundleSpec.ENCODER_SIZE_BYTES,
                TinyWhisperBundleSpec.DECODER_NAME to TinyWhisperBundleSpec.DECODER_SIZE_BYTES,
                TinyWhisperBundleSpec.VOCABULARY_NAME to TinyWhisperBundleSpec.VOCABULARY_SIZE_BYTES,
                TinyWhisperBundleSpec.METADATA_NAME to TinyWhisperBundleSpec.METADATA_SIZE_BYTES,
                TinyWhisperBundleSpec.CONFIG_NAME to TinyWhisperBundleSpec.CONFIG_SIZE_BYTES,
            )
            val expectedHashes = mapOf(
                TinyWhisperBundleSpec.ENCODER_NAME to TinyWhisperBundleSpec.ENCODER_SHA256,
                TinyWhisperBundleSpec.DECODER_NAME to TinyWhisperBundleSpec.DECODER_SHA256,
                TinyWhisperBundleSpec.VOCABULARY_NAME to TinyWhisperBundleSpec.VOCABULARY_SHA256,
                TinyWhisperBundleSpec.METADATA_NAME to TinyWhisperBundleSpec.METADATA_SHA256,
                TinyWhisperBundleSpec.CONFIG_NAME to TinyWhisperBundleSpec.CONFIG_SHA256,
            )
            for ((name, size) in artifacts) {
                val file = TinyWhisperBundleSpec.file(directory, name)
                require(file.isFile && file.canRead() && file.length() == size) {
                    "Whisper artifact is unavailable or has the wrong size: $name"
                }
                val actual = sha256(file)
                require(actual == expectedHashes.getValue(name)) {
                    "Whisper artifact digest mismatch: $name"
                }
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw java.util.concurrent.CancellationException("Whisper transcription cancelled")
}
