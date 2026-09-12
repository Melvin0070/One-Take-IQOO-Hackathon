package com.example.one_take

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderUtilsTest {
    @Test
    fun formatElapsedTimeUsesMinutesAndSeconds() {
        assertEquals("00:00", formatElapsedTime(0L))
        assertEquals("01:05", formatElapsedTime(65_000L))
        assertEquals("01:02:03", formatElapsedTime(3_723_000L))
    }
}
