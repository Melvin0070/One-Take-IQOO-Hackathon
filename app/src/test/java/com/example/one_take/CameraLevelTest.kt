package com.example.one_take

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLevelTest {
    @Test
    fun uprightPortraitReadingIsLevel() {
        val reading = cameraLevelReading(0f, 9.81f, 0f)

        assertTrue(reading.isValid)
        assertEquals(0f, reading.rollDegrees, 0.01f)
    }

    @Test
    fun tiltedPortraitReadingReportsRoll() {
        val angleRadians = Math.toRadians(10.0)
        val reading = cameraLevelReading(
            gravityX = (9.81 * sin(angleRadians)).toFloat(),
            gravityY = (9.81 * cos(angleRadians)).toFloat(),
            gravityZ = 0f
        )

        assertTrue(reading.isValid)
        assertEquals(10f, reading.rollDegrees, 0.01f)
    }

    @Test
    fun faceUpReadingIsHiddenAsUnreliable() {
        val reading = cameraLevelReading(0.1f, 0.1f, 9.81f)

        assertFalse(reading.isValid)
    }
}
