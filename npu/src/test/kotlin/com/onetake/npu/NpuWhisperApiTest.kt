package com.onetake.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuWhisperApiTest {
    @Test
    fun resultExposesSampleIndexedSegmentsAndNpuDiagnostics() {
        val result = NpuCaptionResult(
            segments = listOf(
                NpuCaptionSegment(
                    startSample = 16_000L,
                    endSample = 32_000L,
                    text = "hello",
                ),
            ),
            diagnostics = NpuCaptionDiagnostics(
                modelId = TinyWhisperBundleSpec.MODEL_ID,
                processor = NpuProcessor.NPU,
                encoderCalls = 1,
                decoderCalls = 4,
                elapsedMicros = 2_000L,
            ),
        )

        assertEquals(16_000L, result.segments.single().startSample)
        assertEquals(32_000L, result.segments.single().endSample)
        assertEquals(NpuProcessor.NPU, result.diagnostics.processor)
        assertTrue(result.diagnostics.encoderCalls > 0)
    }

    @Test
    fun sampleIndexedSegmentRejectsReversedBounds() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NpuCaptionSegment(startSample = 10L, endSample = 10L, text = "invalid")
        }
    }
}
