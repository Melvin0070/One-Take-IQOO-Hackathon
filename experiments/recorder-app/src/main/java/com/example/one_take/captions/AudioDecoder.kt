package com.example.one_take.captions

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object AudioDecoder {
    const val TARGET_SAMPLE_RATE = 16_000
    const val MAX_DURATION_MS = 120_000L

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DECODE_WAIT_MS = 300_000L
    private const val MAX_DURATION_US = MAX_DURATION_MS * 1_000L

    suspend fun decodeMono16k(video: File): FloatArray = withContext(Dispatchers.Default) {
        decodeInternal(video)
    }

    private suspend fun decodeInternal(video: File): FloatArray {
        require(video.isFile && video.canRead()) { "Recording is missing or unreadable" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(video.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                val format = extractor.getTrackFormat(index)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("Recording has no audio track")

            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
            if (durationUs != null && durationUs > MAX_DURATION_US) {
                throw IllegalArgumentException("Recording is longer than 120 seconds")
            }
            val allowedDurationUs = durationUs?.coerceAtMost(MAX_DURATION_US) ?: MAX_DURATION_US

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Audio track has no MIME type")
            val trackRate = format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: TARGET_SAMPLE_RATE
            val trackChannels = format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            require(trackRate > 0) { "Audio track has an invalid sample rate" }
            require(trackChannels in 1..8) { "Audio track has an unsupported channel count" }

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            codecStarted = true

            val chunks = ArrayList<DecodedChunk>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputRate = trackRate
            var outputChannels = trackChannels
            var outputEncoding = format.integerOrNull(MediaFormat.KEY_PCM_ENCODING)
                ?: AudioFormat.ENCODING_PCM_16BIT
            var fallbackPtsUs = 0L
            val deadline = SystemClock.elapsedRealtime() + MAX_DECODE_WAIT_MS

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw IllegalStateException("Timed out while decoding audio")
                }

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Audio decoder returned no input buffer")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            val endPtsUs = max(fallbackPtsUs, extractor.sampleTime.takeIf { it >= 0L } ?: fallbackPtsUs)
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                endPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.takeIf { it >= 0L } ?: fallbackPtsUs
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                            fallbackPtsUs = presentationTimeUs
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputRate = outputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                        outputChannels = outputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                        outputEncoding = outputFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: outputEncoding
                        require(outputRate > 0) { "Audio decoder returned an invalid sample rate" }
                        require(outputChannels in 1..8) { "Audio decoder returned an unsupported channel count" }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val pcm = decodePcm(
                                    outputBuffer,
                                    bufferInfo.offset,
                                    bufferInfo.size,
                                    outputEncoding,
                                    outputChannels,
                                )
                                if (pcm.isNotEmpty()) {
                                    val ptsUs = bufferInfo.presentationTimeUs.takeIf { it >= 0L } ?: fallbackPtsUs
                                    val mono16k = resampleMono(pcm, outputRate, outputChannels)
                                    val safePtsUs = ptsUs.coerceAtLeast(0L)
                                    val decodedEndUs = safePtsUs + mono16k.size * 1_000_000L / TARGET_SAMPLE_RATE
                                    if (durationUs == null && decodedEndUs > MAX_DURATION_US) {
                                        throw IllegalArgumentException("Recording is longer than 120 seconds")
                                    }
                                    if (safePtsUs < allowedDurationUs && mono16k.isNotEmpty()) {
                                        val allowedFrames = min(
                                            mono16k.size,
                                            ((allowedDurationUs - safePtsUs) * TARGET_SAMPLE_RATE + 999_999L)
                                                .div(1_000_000L)
                                                .toInt(),
                                        )
                                        if (allowedFrames > 0) {
                                            chunks += DecodedChunk(
                                                safePtsUs,
                                                mono16k.copyOf(allowedFrames),
                                            )
                                        }
                                    }
                                    val frames = pcm.size / outputChannels
                                    fallbackPtsUs = max(
                                        fallbackPtsUs,
                                        ptsUs + frames * 1_000_000L / outputRate,
                                    )
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (chunks.isEmpty()) {
                throw IllegalStateException("Audio decoder produced no PCM samples")
            }
            return buildTimeline(chunks)
        } finally {
            if (codecStarted) {
                runCatching { codec?.stop() }
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodePcm(
        source: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
        channels: Int,
    ): FloatArray = PcmDecoder.decode(source, offset, size, encoding, channels)

    private fun buildTimeline(chunks: List<DecodedChunk>): FloatArray {
        val builder = FloatTimelineBuilder(TARGET_SAMPLE_RATE * (MAX_DURATION_MS / 1_000L).toInt())
        for (chunk in chunks.sortedBy { it.startUs }) {
            val startIndex = ((chunk.startUs * TARGET_SAMPLE_RATE) / 1_000_000L)
                .coerceIn(0L, builder.maxSamples.toLong())
                .toInt()
            if (chunk.samples.isEmpty() || startIndex >= builder.maxSamples) {
                continue
            }
            builder.write(startIndex, chunk.samples)
            if (builder.size >= builder.maxSamples) {
                break
            }
        }
        if (builder.size == 0) {
            throw IllegalStateException("Audio decoder produced no usable samples")
        }
        return builder.toArray()
    }

    private fun resampleMono(samples: FloatArray, sourceRate: Int, channels: Int): FloatArray {
        val frameCount = samples.size / channels
        if (frameCount == 0) {
            return FloatArray(0)
        }
        val targetFrames = ceil(frameCount.toDouble() * TARGET_SAMPLE_RATE / sourceRate).toInt()
        val result = FloatArray(targetFrames)
        for (targetIndex in result.indices) {
            val sourcePosition = targetIndex.toDouble() * sourceRate / TARGET_SAMPLE_RATE
            val sourceIndex = sourcePosition.toInt().coerceAtMost(frameCount - 1)
            val nextIndex = (sourceIndex + 1).coerceAtMost(frameCount - 1)
            val fraction = (sourcePosition - sourceIndex).toFloat()
            var sample = 0f
            var nextSample = 0f
            for (channel in 0 until channels) {
                sample += samples[sourceIndex * channels + channel]
                nextSample += samples[nextIndex * channels + channel]
            }
            result[targetIndex] = ((sample / channels) * (1f - fraction) + (nextSample / channels) * fraction)
                .coerceIn(-1f, 1f)
        }
        return result
    }

    private data class DecodedChunk(
        val startUs: Long,
        val samples: FloatArray,
    )

    private class FloatTimelineBuilder(val maxSamples: Int) {
        private var data = FloatArray(min(64_000, maxSamples.coerceAtLeast(1)))
        var size: Int = 0
            private set

        fun write(startIndex: Int, samples: FloatArray) {
            val end = min(maxSamples, startIndex + samples.size)
            if (end <= startIndex) {
                return
            }
            ensureCapacity(end)
            samples.copyInto(data, startIndex, 0, end - startIndex)
            size = max(size, end)
        }

        fun toArray(): FloatArray = data.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) {
                return
            }
            var capacity = data.size
            while (capacity < required) {
                capacity = min(maxSamples, max(capacity * 2, required))
            }
            data = data.copyOf(capacity)
        }
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}
