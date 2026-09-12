package com.example.one_take.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SilenceDetectorTest {
    @Test
    fun longInteriorPauseProducesAConservativePaddedCut() {
        val sampleRate = 1_000
        val samples = FloatArray(sampleRate * 2 + sampleRate / 2) { index ->
            when {
                index < 400 -> speechSample(index, 0.1)
                index < 1_800 -> 0f
                else -> speechSample(index, 0.1)
            }
        }

        val decision = SilenceDetector.detect(samples, sampleRate)

        assertEquals(2_500L, decision.durationMs)
        assertEquals(1, decision.cuts.size)
        assertEquals(EditCut("silence-600-1600", 600L, 1_600L, "silence"), decision.cuts.single())
    }

    @Test
    fun veryQuietVoicedSectionBetweenLoudAnchorsIsRetained() {
        val samples = FloatArray(2_500) { index ->
            if (index < 400 || index >= 1_800) 0.4f
            else 0.002f + 0.003f * sin(index.toDouble() * 0.31).toFloat()
        }
        assertTrue(SilenceDetector.detect(samples, 1_000).cuts.isEmpty())
    }

    @Test
    fun pureSilenceIsNeverTrimmed() {
        val decision = SilenceDetector.detect(FloatArray(2_500), 1_000)

        assertEquals(2_500L, decision.durationMs)
        assertTrue(decision.cuts.isEmpty())
    }

    @Test
    fun lowLevelRoomNoiseWithoutStrongSpeechIsNotTrimmed() {
        val samples = FloatArray(2_500) { index ->
            0.008f * sin(index.toDouble() * 0.2).toFloat()
        }

        assertTrue(SilenceDetector.detect(samples, 1_000).cuts.isEmpty())
    }

    @Test
    fun quietSpeechAfterALoudAnchorIsNotTrimmed() {
        val samples = FloatArray(2_500) { index ->
            val amplitude = if (index < 1_250) 0.1 else 0.015
            (amplitude * sin(index.toDouble() * 0.31)).toFloat()
        }

        assertTrue(SilenceDetector.detect(samples, 1_000).cuts.isEmpty())
    }

    private fun speechSample(index: Int, amplitude: Double): Float =
        (amplitude * sin(index.toDouble() * 0.31)).toFloat()
}
