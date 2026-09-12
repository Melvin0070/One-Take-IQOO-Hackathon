package com.onetake.engine.android.vision

import android.graphics.Bitmap
import android.graphics.Color

/** A review-only visual hint produced from one preview frame. */
data class VisualSuggestion(
    val kind: Kind,
    val message: String,
    val severity: Severity,
) {
    init {
        require(message.isNotBlank()) { "Visual suggestion message must not be blank" }
        require('\n' !in message && '\r' !in message) {
            "Visual suggestion message must be one line"
        }
    }

    enum class Kind {
        TOO_DARK,
        BLOWN_OUT,
        MOVE_TO_CENTER,
        MOVE_CLOSER,
    }

    enum class Severity {
        INFO,
        WARNING,
    }
}

/**
 * Runs cheap, bounded heuristics over a single camera frame.
 *
 * This class has no state or scheduling. Callers choose when to run it, and
 * the frame is sampled instead of being copied or resized for analysis.
 */
class VisualAnalyzer {
    /** Returns actionable hints for [frame] and its optional detected face. */
    fun analyze(frame: Bitmap, face: FaceObservation?): List<VisualSuggestion> {
        require(!frame.isRecycled) { "Cannot analyze a recycled bitmap" }

        val statistics = frameStatistics(frame)
        val suggestions = ArrayList<VisualSuggestion>(3)
        if (statistics.meanLuminance < DARK_MEAN_THRESHOLD ||
            (statistics.darkClippedRatio >= CLIPPED_RATIO &&
                statistics.meanLuminance < DARK_MEAN_GUARD)
        ) {
            suggestions += VisualSuggestion(
                kind = VisualSuggestion.Kind.TOO_DARK,
                message = "Add more light to the scene",
                severity = VisualSuggestion.Severity.WARNING,
            )
        }
        if (statistics.meanLuminance > BLOWN_OUT_MEAN_THRESHOLD ||
            (statistics.brightClippedRatio >= CLIPPED_RATIO &&
                statistics.meanLuminance > BLOWN_OUT_MEAN_GUARD)
        ) {
            suggestions += VisualSuggestion(
                kind = VisualSuggestion.Kind.BLOWN_OUT,
                message = "Reduce the light to preserve highlight detail",
                severity = VisualSuggestion.Severity.WARNING,
            )
        }

        if (face != null) {
            if (face.centerX < RULE_OF_THIRDS_LEFT ||
                face.centerX > RULE_OF_THIRDS_RIGHT
            ) {
                suggestions += VisualSuggestion(
                    kind = VisualSuggestion.Kind.MOVE_TO_CENTER,
                    message = "Move toward the centre of the frame",
                    severity = VisualSuggestion.Severity.INFO,
                )
            }
            if (face.height < MIN_FACE_HEIGHT) {
                suggestions += VisualSuggestion(
                    kind = VisualSuggestion.Kind.MOVE_CLOSER,
                    message = "Move closer so your face fills the frame",
                    severity = VisualSuggestion.Severity.INFO,
                )
            }
        }
        return suggestions
    }

    private fun frameStatistics(frame: Bitmap): FrameStatistics {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return FrameStatistics.EMPTY

        val columns = minOf(width, MAX_GRID_SIZE)
        val rows = minOf(height, MAX_GRID_SIZE)
        var sampledPixels = 0
        var luminanceTotal = 0.0
        var darkClippedPixels = 0
        var brightClippedPixels = 0
        for (row in 0 until rows) {
            val y = sampleCoordinate(row, rows, height)
            for (column in 0 until columns) {
                val x = sampleCoordinate(column, columns, width)
                val color = frame.getPixel(x, y)
                val luminance = luminance(color)
                luminanceTotal += luminance
                if (luminance <= DARK_CLIP_LUMINANCE) darkClippedPixels++
                if (luminance >= BRIGHT_CLIP_LUMINANCE) brightClippedPixels++
                sampledPixels++
            }
        }

        val sampleCount = sampledPixels.coerceAtLeast(1)
        return FrameStatistics(
            meanLuminance = (luminanceTotal / sampleCount).toFloat(),
            darkClippedRatio = darkClippedPixels.toFloat() / sampleCount,
            brightClippedRatio = brightClippedPixels.toFloat() / sampleCount,
        )
    }

    private fun sampleCoordinate(index: Int, sampleCount: Int, size: Int): Int {
        return (((index + 0.5) * size) / sampleCount).toInt().coerceIn(0, size - 1)
    }

    private fun luminance(color: Int): Float {
        return (
            RED_LUMA * Color.red(color) +
                GREEN_LUMA * Color.green(color) +
                BLUE_LUMA * Color.blue(color)
            ) / MAX_CHANNEL_VALUE
    }

    private data class FrameStatistics(
        val meanLuminance: Float,
        val darkClippedRatio: Float,
        val brightClippedRatio: Float,
    ) {
        companion object {
            val EMPTY = FrameStatistics(0f, 0f, 0f)
        }
    }

    private companion object {
        const val MAX_GRID_SIZE = 128
        const val MAX_CHANNEL_VALUE = 255f
        const val RED_LUMA = 0.2126f
        const val GREEN_LUMA = 0.7152f
        const val BLUE_LUMA = 0.0722f
        const val DARK_CLIP_LUMINANCE = 0.05f
        const val BRIGHT_CLIP_LUMINANCE = 0.95f
        const val CLIPPED_RATIO = 0.50f
        const val DARK_MEAN_THRESHOLD = 0.22f
        const val DARK_MEAN_GUARD = 0.40f
        const val BLOWN_OUT_MEAN_THRESHOLD = 0.82f
        const val BLOWN_OUT_MEAN_GUARD = 0.65f
        const val RULE_OF_THIRDS_LEFT = 1f / 3f
        const val RULE_OF_THIRDS_RIGHT = 2f / 3f
        const val MIN_FACE_HEIGHT = 0.20f
    }
}
