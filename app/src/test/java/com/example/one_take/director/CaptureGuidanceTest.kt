package com.example.one_take.director

import com.example.one_take.vision.FaceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureGuidanceTest {
    @Test
    fun tooCloseNeedsTwoStableObservationsBeforePrompting() {
        val guidance = CaptureGuidance()

        assertNull(guidance.observe(observation(width = 0.52f), 0L))
        assertNull(guidance.observe(observation(width = 0.52f), 300L))
        assertEquals(
            CaptureGuidancePrompt("close", "Step back, you're too close", 500L),
            guidance.observe(observation(width = 0.52f), 500L),
        )
    }

    @Test
    fun anUnstableConditionIsResetWhenTheFaceReturnsToSafeBounds() {
        val guidance = CaptureGuidance()

        assertNull(guidance.observe(observation(width = 0.52f), 0L))
        assertNull(guidance.observe(observation(width = 0.34f), 300L))
        assertNull(guidance.observe(observation(width = 0.52f), 500L))
        assertNull(guidance.observe(observation(width = 0.52f), 900L))
        assertEquals("Step back, you're too close", guidance.observe(observation(width = 0.52f), 1_000L)?.message)
    }

    @Test
    fun offCenterAndBacklightConditionsAlsoRequireStableEvidence() {
        val guidance = CaptureGuidance()

        assertNull(guidance.observe(observation(centerX = 0.20f), 0L))
        assertEquals(
            "Center yourself",
            guidance.observe(observation(centerX = 0.20f), 500L)?.message,
        )
        guidance.reset()
        assertNull(guidance.observe(observation(faceLuminance = 0.20f, backgroundLuminance = 0.50f), 0L))
        assertEquals(
            "You're backlit, turn 90°",
            guidance.observe(observation(faceLuminance = 0.20f, backgroundLuminance = 0.50f), 500L)?.message,
        )
    }

    @Test
    fun onePromptPerConditionEpisodeAvoidsNagging() {
        val guidance = CaptureGuidance()

        assertNull(guidance.observe(observation(width = 0.52f), 0L))
        assertEquals("Step back, you're too close", guidance.observe(observation(width = 0.52f), 500L)?.message)
        assertNull(guidance.observe(observation(width = 0.52f), 9_000L))
        assertNull(guidance.observe(observation(width = 0.34f), 9_100L))
        assertNull(guidance.observe(observation(width = 0.52f), 9_200L))
        assertEquals("Step back, you're too close", guidance.observe(observation(width = 0.52f), 9_700L)?.message)
    }

    @Test
    fun missingFaceAndBackwardsTimeNeverCreateAStableCondition() {
        val guidance = CaptureGuidance()

        assertNull(guidance.observe(observation(width = 0.52f), 1_000L))
        assertNull(guidance.observe(null, 1_100L))
        assertNull(guidance.observe(observation(width = 0.52f), 500L))
        assertNull(guidance.observe(observation(width = 0.52f), 1_400L))
        assertEquals("Step back, you're too close", guidance.observe(observation(width = 0.52f), 1_601L)?.message)
    }

    private fun observation(
        centerX: Float = 0.5f,
        width: Float = 0.34f,
        faceLuminance: Float = 0.55f,
        backgroundLuminance: Float = 0.50f,
    ): FaceObservation = FaceObservation(
        timestampMs = 0L,
        centerX = centerX,
        centerY = 0.45f,
        width = width,
        height = 0.45f,
        faceLuminance = faceLuminance,
        backgroundLuminance = backgroundLuminance,
        offAxis = false,
    )
}
