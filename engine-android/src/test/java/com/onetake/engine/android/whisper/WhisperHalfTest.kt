package com.onetake.engine.android.whisper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WhisperHalfTest {
    @Test fun finiteHalfEncodingsRoundTripExactly() {
        for (bits in 0..65535) {
            if (bits and 0x7c00 == 0x7c00 && bits and 0x03ff != 0) continue
            val bytes = byteArrayOf(bits.toByte(), (bits ushr 8).toByte())
            assertArrayEquals("Half bits $bits", bytes, halfBytes(halfFloats(bytes)))
        }
    }

    @Test fun floatToHalfUsesNearestEvenRoundingAndLittleEndian() {
        val values = floatArrayOf(0f, -0f, 1f, -2f, 65504f, 1f / 16777216f,
            1.00048828125f, 1.00146484375f)
        val expected = intArrayOf(0, 0x8000, 0x3c00, 0xc000, 0x7bff, 1, 0x3c00, 0x3c02)
        val bytes = ByteBuffer.wrap(halfBytes(values)).order(ByteOrder.LITTLE_ENDIAN)
        expected.forEach { assertEquals(it, bytes.short.toInt() and 0xffff) }
    }
}
