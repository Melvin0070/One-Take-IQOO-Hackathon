package com.onetake.engine.android.vision

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisualAnalyzerTest {
    private val analyzer = VisualAnalyzer()

    @Test
    fun darkFrameProducesTooDarkSuggestion() {
        val suggestions = analyzer.analyze(solidFrame(Color.rgb(24, 24, 24)), face = null)

        assertEquals(
            listOf(VisualSuggestion.Kind.TOO_DARK),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun blownOutFrameProducesBlownOutSuggestion() {
        val suggestions = analyzer.analyze(solidFrame(Color.WHITE), face = null)

        assertEquals(
            listOf(VisualSuggestion.Kind.BLOWN_OUT),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun darkHistogramClippingProducesSuggestionWhenMeanIsAboveDarkThreshold() {
        val suggestions = analyzer.analyze(
            stripedFrame(
                firstColor = Color.BLACK,
                firstColumns = 19,
                secondColor = Color.rgb(200, 200, 200),
            ),
            face = null,
        )

        assertEquals(
            listOf(VisualSuggestion.Kind.TOO_DARK),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun brightHistogramClippingProducesSuggestionWhenMeanIsBelowBrightThreshold() {
        val suggestions = analyzer.analyze(
            stripedFrame(
                firstColor = Color.WHITE,
                firstColumns = 19,
                secondColor = Color.rgb(80, 80, 80),
            ),
            face = null,
        )

        assertEquals(
            listOf(VisualSuggestion.Kind.BLOWN_OUT),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun faceFarLeftProducesMoveToCenterSuggestion() {
        val suggestions = analyzer.analyze(
            solidFrame(Color.rgb(128, 128, 128)),
            face = face(centerX = 0.15f),
        )

        assertEquals(
            listOf(VisualSuggestion.Kind.MOVE_TO_CENTER),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun faceTooSmallProducesMoveCloserSuggestion() {
        val suggestions = analyzer.analyze(
            solidFrame(Color.rgb(128, 128, 128)),
            face = face(height = 0.12f),
        )

        assertEquals(
            listOf(VisualSuggestion.Kind.MOVE_CLOSER),
            suggestions.map(VisualSuggestion::kind),
        )
    }

    @Test
    fun wellExposedCenteredFrameProducesNoSuggestions() {
        val suggestions = analyzer.analyze(
            solidFrame(Color.rgb(128, 128, 128)),
            face = face(),
        )

        assertEquals(emptyList<VisualSuggestion>(), suggestions)
    }

    private fun solidFrame(color: Int): Bitmap {
        return Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    private fun stripedFrame(
        firstColor: Int,
        firstColumns: Int,
        secondColor: Int,
    ): Bitmap {
        val width = 32
        val height = 24
        val pixels = IntArray(width * height) { index ->
            if (index % width < firstColumns) firstColor else secondColor
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun face(
        centerX: Float = 0.5f,
        height: Float = 0.45f,
    ): FaceObservation = FaceObservation(
        timestampMs = 0L,
        centerX = centerX,
        centerY = 0.45f,
        width = 0.30f,
        height = height,
        faceLuminance = 0.55f,
        backgroundLuminance = 0.50f,
        offAxis = false,
    )
}
