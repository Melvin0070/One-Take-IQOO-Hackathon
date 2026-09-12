package com.example.one_take.captions

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts decoded PCM bytes to normalized interleaved floats.
 *
 * MediaCodec returns a ByteBuffer whose byte order is not part of the output
 * contract, so PCM samples are always interpreted as little-endian here. The
 * typed formats use bulk view-buffer reads to avoid crossing the ByteBuffer
 * boundary once per sample.
 */
internal object PcmDecoder {
    fun decode(
        source: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
        channels: Int,
    ): FloatArray {
        if (offset < 0 || size <= 0 || offset > source.capacity() || size > source.capacity() - offset) {
            return FloatArray(0)
        }
        if (channels !in 1..8) {
            return FloatArray(0)
        }

        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_32BIT,
            -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> throw IllegalArgumentException("Unsupported PCM encoding: $encoding")
        }
        val frameSize = bytesPerSample * channels
        if (frameSize <= 0 || size < frameSize) {
            return FloatArray(0)
        }

        val frameCount = size / frameSize
        val sampleCount = frameCount * channels
        val completeByteCount = frameCount * frameSize
        val result = FloatArray(sampleCount)
        val buffer = source.duplicate()
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                // BufferInfo.offset/size describe the backing capacity, even
                // when a codec has left the returned view's limit elsewhere.
                clear()
                position(offset)
                limit(offset + completeByteCount)
            }

        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> decode8Bit(buffer, result)
            AudioFormat.ENCODING_PCM_16BIT -> decode16Bit(buffer, result)
            AudioFormat.ENCODING_PCM_FLOAT -> decodeFloat(buffer, result)
            AudioFormat.ENCODING_PCM_32BIT -> decode32Bit(buffer, result)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> decode24Bit(buffer, result)
            else -> error("unreachable")
        }
        return result
    }

    private fun decode8Bit(buffer: ByteBuffer, result: FloatArray) {
        val values = ByteArray(result.size)
        buffer.get(values)
        for (index in result.indices) {
            result[index] = ((values[index].toInt() and 0xff) - 128) / 128f
        }
    }

    private fun decode16Bit(buffer: ByteBuffer, result: FloatArray) {
        val values = ShortArray(result.size)
        buffer.asShortBuffer().get(values)
        for (index in result.indices) {
            result[index] = values[index] / 32768f
        }
    }

    private fun decodeFloat(buffer: ByteBuffer, result: FloatArray) {
        buffer.asFloatBuffer().get(result)
        for (index in result.indices) {
            result[index] = result[index].coerceIn(-1f, 1f)
        }
    }

    private fun decode32Bit(buffer: ByteBuffer, result: FloatArray) {
        val values = IntArray(result.size)
        buffer.asIntBuffer().get(values)
        for (index in result.indices) {
            result[index] = values[index] / 2_147_483_648f
        }
    }

    private fun decode24Bit(buffer: ByteBuffer, result: FloatArray) {
        val values = ByteArray(result.size * 3)
        buffer.get(values)
        for (index in result.indices) {
            val byteIndex = index * 3
            val value = (values[byteIndex].toInt() and 0xff) or
                ((values[byteIndex + 1].toInt() and 0xff) shl 8) or
                ((values[byteIndex + 2].toInt() and 0xff) shl 16)
            val signed = if (value and 0x0080_0000 != 0) value or -0x0100_0000 else value
            result[index] = signed / 8_388_608f
        }
    }
}
