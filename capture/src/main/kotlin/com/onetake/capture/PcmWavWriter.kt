package com.onetake.capture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Small PCM16 WAV writer used for every capture, including Architecture B. */
internal class PcmWavWriter private constructor(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val output: BufferedOutputStream,
) : AutoCloseable {
    private var dataBytes = 0L
    private var closed = false

    /** Writes little-endian signed PCM16 samples. */
    @Synchronized
    fun write(samples: ShortArray) {
        check(!closed) { "WAV writer is closed" }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size.toLong()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.flush()
        output.close()
        require(dataBytes <= 0xffff_ffffL - 36L) { "WAV file exceeds RIFF 32-bit size" }
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(4L)
            writeLittleEndian32(randomAccess, dataBytes + 36L)
            randomAccess.seek(40L)
            writeLittleEndian32(randomAccess, dataBytes)
        }
    }

    companion object {
        fun open(file: File, sampleRate: Int, channels: Int): PcmWavWriter {
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(channels > 0) { "channels must be positive" }
            file.parentFile?.mkdirs()
            val output = BufferedOutputStream(FileOutputStream(file, false))
            try {
                val header = ByteArray(44)
                putAscii(header, 0, "RIFF")
                putAscii(header, 8, "WAVE")
                putAscii(header, 12, "fmt ")
                putLittleEndian32(header, 16, 16L)
                putLittleEndian16(header, 20, 1)
                putLittleEndian16(header, 22, channels)
                putLittleEndian32(header, 24, sampleRate.toLong())
                val blockAlign = channels * 2
                putLittleEndian32(header, 28, sampleRate.toLong() * blockAlign)
                putLittleEndian16(header, 32, blockAlign)
                putLittleEndian16(header, 34, 16)
                putAscii(header, 36, "data")
                output.write(header)
                return PcmWavWriter(file, sampleRate, channels, output)
            } catch (error: Exception) {
                runCatching { output.close() }
                throw error
            }
        }

        private fun putAscii(target: ByteArray, offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
        }

        private fun putLittleEndian16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xff).toByte()
            target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }

        private fun putLittleEndian32(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 4) target[offset + index] = (value ushr (8 * index)).toByte()
        }

        private fun writeLittleEndian32(file: RandomAccessFile, value: Long) {
            for (index in 0 until 4) file.write((value ushr (8 * index)).toInt() and 0xff)
        }
    }
}
