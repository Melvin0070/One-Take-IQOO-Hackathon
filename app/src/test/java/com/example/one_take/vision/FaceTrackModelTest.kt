package com.example.one_take.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackModelTest {
    @Test
    fun observationAndSampleKeepNormalizedCaptureCoordinates() {
        val observation = FaceObservation(
            timestampMs = 4_200L,
            centerX = 0.5f,
            centerY = 0.25f,
            width = 0.4f,
            height = 0.6f,
            faceLuminance = 0.8f,
            backgroundLuminance = 0.3f,
            offAxis = true
        )
        val sample = FaceSample(
            timeMs = observation.timestampMs,
            centerX = observation.centerX,
            centerY = observation.centerY,
            faceWidth = observation.width
        )

        assertEquals(4_200L, sample.timeMs)
        assertEquals(0.5f, sample.centerX, 0f)
        assertEquals(0.25f, sample.centerY, 0f)
        assertEquals(0.4f, sample.faceWidth, 0f)
        assertTrue(observation.offAxis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sampleRejectsNegativeRecordingTime() {
        FaceSample(timeMs = -1L, centerX = 0.5f, centerY = 0.5f, faceWidth = 0.2f)
    }
}
