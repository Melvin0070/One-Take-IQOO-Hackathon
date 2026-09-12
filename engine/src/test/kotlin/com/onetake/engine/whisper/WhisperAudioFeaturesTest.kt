package com.onetake.engine.whisper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAudioFeaturesTest {
    @Test
    fun extractsTheFixedWhisperShapeAndMatchesReferenceValues() {
        val features = WhisperAudioFeatures.extract(shortWaveform())

        assertEquals(WhisperAudioFeatures.FEATURE_COUNT, features.size)
        assertEquals(
            "70f2037b3776dcd1d7b49b1f49e0d1291328b5594a4de4152f31bfac8ba007a4",
            sha256(features),
        )
        assertEquals(1.4778032f, features.maxOrNull()!!, 0.00002f)
        assertEquals(-0.52219677f, features.minOrNull()!!, 0.00002f)
        assertFeature(features, 0, 0, 1.1368883f)
        assertFeature(features, 0, 1, 0.9215684f)
        assertFeature(features, 0, 100, 0.7462370f)
        assertFeature(features, 10, 10, 1.3883339f)
        assertFeature(features, 20, 100, 0.58938646f)
        assertFeature(features, 40, 100, 0.52259105f)
        assertFeature(features, 79, 100, -0.19370556f)
        assertFeature(features, 20, 0, 0.7422997f)
        assertFeature(features, 10, 50, 1.3883282f)
        assertFeature(features, 79, 2999, -0.52219677f)
    }

    @Test
    fun reflectsTheRightEdgeOfAFullLengthWaveform() {
        val features = WhisperAudioFeatures.extract(fullWaveform())

        // These last-frame values depend on the 200-sample center pad and its
        // edge-excluding reflection, rather than a zero or repeated-edge pad.
        assertEquals(
            "c60735c8cf4c095d3538624bd6d3197e3a67288c8826258c339bedfc14e22dd8",
            sha256(features),
        )
        assertFeature(features, 0, 2999, 0.5430106f)
        assertFeature(features, 1, 2999, 0.56782514f)
        assertFeature(features, 10, 2999, 0.38869685f)
        assertFeature(features, 20, 2999, 0.7268191f)
        assertFeature(features, 40, 2999, -0.12872648f)
        assertFeature(features, 79, 2999, -0.35599732f)
        assertFeature(features, 33, 2345, 0.02653962f)
        assertFeature(features, 55, 2345, -0.25340354f)
    }

    @Test
    fun padsShortInputAndTreatsEmptyInputAsSilence() {
        val features = WhisperAudioFeatures.extract(FloatArray(0))

        assertTrue(features.all { it == -1.5f })
        val shortFeatures = WhisperAudioFeatures.extract(FloatArray(1) { 0.25f })
        assertEquals(WhisperAudioFeatures.FEATURE_COUNT, shortFeatures.size)
        assertTrue(shortFeatures.all { it.isFinite() })
    }

    @Test
    fun truncatesInputAfterThirtySeconds() {
        val waveform = fullWaveform()
        val longer = FloatArray(WhisperAudioFeatures.MAX_AUDIO_SAMPLES + 257)
        waveform.copyInto(longer)
        longer[WhisperAudioFeatures.MAX_AUDIO_SAMPLES] = -0.99f

        val expected = WhisperAudioFeatures.extract(waveform)
        val actual = WhisperAudioFeatures.extract(longer)
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals(expected[index], actual[index], 0.0f)
        }
    }

    @Test(expected = CancellationException::class)
    fun cancellationStopsBeforeProcessingFrames() {
        WhisperAudioFeatures.extract(FloatArray(WhisperAudioFeatures.MAX_AUDIO_SAMPLES)) { true }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFinitePcm() {
        WhisperAudioFeatures.extract(floatArrayOf(0.0f, Float.NaN))
    }

    private fun assertFeature(features: FloatArray, mel: Int, frame: Int, expected: Float) {
        assertEquals(expected, features[mel * WhisperAudioFeatures.FRAME_COUNT + frame], 0.00002f)
    }

    private fun sha256(features: FloatArray): String {
        val bytes = ByteBuffer.allocate(features.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        features.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private fun shortWaveform(): FloatArray = FloatArray(WhisperAudioFeatures.SAMPLE_RATE) { index ->
        val sample = index.toDouble()
        (
            0.6 * sin(2.0 * Math.PI * 440.0 * sample / WhisperAudioFeatures.SAMPLE_RATE) +
                0.2 * sin(2.0 * Math.PI * 1234.0 * sample / WhisperAudioFeatures.SAMPLE_RATE) +
                0.05 * cos(2.0 * Math.PI * 61.0 * sample / WhisperAudioFeatures.SAMPLE_RATE)
            ).toFloat()
    }

    private fun fullWaveform(): FloatArray = FloatArray(WhisperAudioFeatures.MAX_AUDIO_SAMPLES) { index ->
        val sample = index.toDouble()
        val periodic = (index % 97 - 48) / 5000.0
        (
            0.35 * sin(2.0 * Math.PI * 220.0 * sample / WhisperAudioFeatures.SAMPLE_RATE) +
                0.21 * cos(2.0 * Math.PI * 697.0 * sample / WhisperAudioFeatures.SAMPLE_RATE) +
                periodic
            ).toFloat()
    }
}
