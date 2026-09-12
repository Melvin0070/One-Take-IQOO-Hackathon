package com.example.one_take.captions

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDecoderTest {
    @Test
    fun rejectsInvalidRangesAndIncompleteFrames() {
        val source = ByteBuffer.allocate(4)

        assertTrue(PcmDecoder.decode(source, -1, 2, AudioFormat.ENCODING_PCM_16BIT, 1).isEmpty())
        assertTrue(PcmDecoder.decode(source, 0, 5, AudioFormat.ENCODING_PCM_16BIT, 1).isEmpty())
        assertTrue(PcmDecoder.decode(source, 4, 1, AudioFormat.ENCODING_PCM_8BIT, 1).isEmpty())
        assertTrue(PcmDecoder.decode(source, 0, 1, AudioFormat.ENCODING_PCM_16BIT, 1).isEmpty())
    }

    @Test
    fun readsWithinCapacityWithoutMutatingSourcePositionOrLimit() {
        val source = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        source.put(2, 0x34)
        source.put(3, 0x12)
        source.position(0).limit(2)

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 2,
            size = 2,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            channels = 1,
        )

        assertArrayEquals(floatArrayOf(0x1234 / 32768f), decoded, 0f)
        assertEquals(0, source.position())
        assertEquals(2, source.limit())
    }

    @Test
    fun decodesLittleEndian16BitStereoAtOffsetAndDropsTrailingBytes() {
        val source = ByteBuffer.allocate(1 + 8 + 1).order(ByteOrder.BIG_ENDIAN)
        source.put(0x7f)
        putLittleEndianShort(source, 0x1234)
        putLittleEndianShort(source, -32768)
        putLittleEndianShort(source, 32767)
        putLittleEndianShort(source, -1)
        source.put(0x55)

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 1,
            size = 9,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            channels = 2,
        )

        assertArrayEquals(
            floatArrayOf(0x1234 / 32768f, -1f, 32767 / 32768f, -1 / 32768f),
            decoded,
            0f,
        )
    }

    @Test
    fun decodesClampedFloat32Samples() {
        val source = ByteBuffer.allocate(2 + 12 + 2).order(ByteOrder.LITTLE_ENDIAN)
        source.putShort(0x1357)
        source.putFloat(-2f)
        source.putFloat(0.25f)
        source.putFloat(2f)
        source.putShort(0x2468)

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 2,
            size = 14,
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            channels = 1,
        )

        assertArrayEquals(floatArrayOf(-1f, 0.25f, 1f), decoded, 0f)
    }

    @Test
    fun decodesLittleEndianSigned32BitSamples() {
        val source = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        putLittleEndianInt(source, Int.MIN_VALUE)
        putLittleEndianInt(source, 1_073_741_824)
        putLittleEndianInt(source, Int.MAX_VALUE)
        source.put(0x7f)
        source.put(0x7f)
        source.put(0x7f)
        source.put(0x7f)

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 0,
            size = 13,
            encoding = AudioFormat.ENCODING_PCM_32BIT,
            channels = 1,
        )

        assertArrayEquals(floatArrayOf(-1f, 0.5f, Int.MAX_VALUE / 2_147_483_648f), decoded, 0f)
    }

    @Test
    fun decodesSignedPacked24BitSamples() {
        val source = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        source.put(0x00.toByte()).put(0x00.toByte()).put(0x80.toByte()) // -1.0
        source.put(0xff.toByte()).put(0xff.toByte()).put(0x7f) // largest positive
        source.put(0x00.toByte()).put(0x00.toByte()).put(0x40.toByte()) // 0.5
        source.put(0x00.toByte()).put(0x00.toByte()).put(0x00.toByte())

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 0,
            size = source.position(),
            encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
            channels = 1,
        )

        assertArrayEquals(floatArrayOf(-1f, 8_388_607 / 8_388_608f, 0.5f, 0f), decoded, 0f)
    }

    @Test
    fun decodesUnsigned8BitSamples() {
        val source = ByteBuffer.wrap(byteArrayOf(0x00, 0x80.toByte(), 0xff.toByte(), 0x55))

        val decoded = PcmDecoder.decode(
            source = source,
            offset = 0,
            size = 4,
            encoding = AudioFormat.ENCODING_PCM_8BIT,
            channels = 1,
        )

        assertArrayEquals(floatArrayOf(-1f, 0f, 127 / 128f, (0x55 - 128) / 128f), decoded, 0f)
        assertEquals(4, decoded.size)
    }

    private fun putLittleEndianShort(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xff).toByte())
        buffer.put((value shr 8 and 0xff).toByte())
    }

    private fun putLittleEndianInt(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xff).toByte())
        buffer.put((value shr 8 and 0xff).toByte())
        buffer.put((value shr 16 and 0xff).toByte())
        buffer.put((value shr 24 and 0xff).toByte())
    }
}
