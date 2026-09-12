package com.onetake.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PcmWavWriterTest {
    @Test
    fun writesPcm16HeaderAndLittleEndianSamples() {
        val directory = Files.createTempDirectory("onetake-wav").toFile()
        try {
            val output = directory.resolve("take.wav")
            PcmWavWriter.open(output, sampleRate = 16_000, channels = 1).use { writer ->
                writer.write(shortArrayOf(0x0102, -2, 0))
            }

            val bytes = output.readBytes()
            assertEquals(50, bytes.size)
            assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
            assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
            assertEquals(42, unsignedLittleEndian32(bytes, 4))
            assertEquals(6, unsignedLittleEndian32(bytes, 40))
            assertEquals(0x02, bytes[44].toInt() and 0xff)
            assertEquals(0x01, bytes[45].toInt() and 0xff)
            assertEquals(0xfe, bytes[46].toInt() and 0xff)
            assertEquals(0xff, bytes[47].toInt() and 0xff)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeIsIdempotentAndEmptyFileHasFinalizedHeader() {
        val directory = Files.createTempDirectory("onetake-wav-empty").toFile()
        try {
            val output = directory.resolve("empty.wav")
            val writer = PcmWavWriter.open(output, sampleRate = 16_000, channels = 1)
            writer.close()
            writer.close()
            val bytes = output.readBytes()
            assertEquals(44, bytes.size)
            assertEquals(36, unsignedLittleEndian32(bytes, 4))
            assertEquals(0, unsignedLittleEndian32(bytes, 40))
            assertTrue(output.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun unsignedLittleEndian32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
}
