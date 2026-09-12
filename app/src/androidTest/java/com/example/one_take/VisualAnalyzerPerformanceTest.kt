package com.example.one_take

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import com.onetake.engine.android.vision.VisualAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAnalyzerPerformanceTest {
    @Test fun analyzes1080pFrameWithinDemoBudget() {
        val frame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            frame.eraseColor(Color.rgb(128, 128, 128))
            val analyzer = VisualAnalyzer()
            val durations = List(10) {
                val start = SystemClock.elapsedRealtimeNanos()
                val suggestions = analyzer.analyze(frame, null)
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                assertTrue("A neutral frame should need no suggestions", suggestions.isEmpty())
                elapsedMs
            }
            println("VisualAnalyzer 1920x1080 on ${Build.MODEL}: milliseconds=$durations")
            assertTrue("Analysis exceeded 200 ms: $durations", durations.all { it < 200.0 })
        } finally {
            frame.recycle()
        }
    }
}
