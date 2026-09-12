package com.example.one_take

import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.editing.analyzePauses
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled speech/noise evidence, not a claim about arbitrary real rooms. */
class NoisyPauseTest {
    @Test fun encodedRoomAudioRetainsAConfirmablePause() {
        withSource("room-confirmation.wav") { source ->
            val audio = kotlinx.coroutines.runBlocking { com.example.one_take.captions.AudioDecoder.decodeMono16k(source) }
            val cuts = analyzePauses(source, audio, InstrumentationRegistry.getInstrumentation().targetContext).cuts
            assertTrue("Encoded audio uncertainty must not erase every long pause: $cuts", cuts.any {
                it.startMs >= 2_000 && it.endMs <= 5_300 && it.endMs - it.startMs >= 800
            })
            assertTrue("Speech must remain outside automatic cuts: $cuts", cuts.all {
                (it.startMs >= 1_850 && it.endMs <= 5_300) || (it.startMs >= 6_200 && it.endMs <= 9_350)
            })
        }
    }

    @Test fun capturedRoomNoiseDoesNotMaskBothKnownPauses() {
        withSource("room-recording-regression.mp4") { source ->
            val audio = kotlinx.coroutines.runBlocking {
                com.example.one_take.captions.AudioDecoder.decodeMono16k(source)
            }
            val cuts = analyzePauses(source, audio, InstrumentationRegistry.getInstrumentation().targetContext).cuts
            assertTrue("Expected a cut through the first recorded pause: $cuts", cuts.any {
                it.startMs >= 2_000 && it.endMs <= 5_300 && it.endMs - it.startMs >= 800
            })
            assertTrue("Expected a cut through the second recorded pause: $cuts", cuts.any {
                it.startMs >= 6_200 && it.endMs <= 9_350 && it.endMs - it.startMs >= 800
            })
            assertTrue("Cuts must not consume the surrounding spoken lines: $cuts", cuts.all {
                (it.startMs >= 1_850 && it.endMs <= 5_300) ||
                    (it.startMs >= 6_200 && it.endMs <= 9_350)
            })
        }
    }

    @Test fun savedAudioFindsAnInteriorPauseUnderSteadyRoomNoise() {
        val audio = fixture(0.7f, false)
        withSource { source ->
            val cuts = analyzePauses(source, audio, InstrumentationRegistry.getInstrumentation().targetContext).cuts
            assertTrue("Expected a confirmed cut inside the known 2.4–5.4s pause: $cuts", cuts.any {
                it.startMs >= 2_400 && it.endMs <= 5_400 && it.endMs - it.startMs >= 1_000
            })
            assertTrue("A cut must not remove the surrounding spoken lines: $cuts", cuts.all {
                it.startMs >= 2_400 && it.endMs <= 5_400
            })
        }
    }

    @Test fun softSpeechBetweenLouderLinesIsRetained() {
        val speech = speech()
        val random = Random(23)
        val audio = FloatArray(16_000 * 8) { i ->
            val gain = if (i in 38_400 until 86_400) 0.025f else 0.7f
            speech[i % speech.size] * gain + (random.nextFloat() - 0.5f) * 0.0003f
        }
        withSource { source ->
            val cuts = analyzePauses(source, audio, InstrumentationRegistry.getInstrumentation().targetContext).cuts
            assertTrue("Quiet spoken material cannot become a long automatic cut: $cuts", cuts.none {
                it.startMs < 5_400 && it.endMs > 2_400
            })
        }
    }

    @Test fun fluctuatingNoiseCannotWidenCutsIntoSpeech() {
        withSource { source ->
            val cuts = analyzePauses(source, fixture(0.7f, true), InstrumentationRegistry.getInstrumentation().targetContext).cuts
            assertTrue("Uncertain noise may miss a pause, but cuts must stay inside it: $cuts", cuts.all {
                it.startMs >= 2_400 && it.endMs <= 5_400
            })
        }
    }

    private fun fixture(gain: Float, fluctuating: Boolean): FloatArray {
        val speech = speech()
        val random = Random(42)
        return FloatArray(16_000 * 8) { i ->
            val t = i / 16_000.0
            val noiseScale = if (fluctuating) (0.004 + 0.003 * sin(t * 1.7)).toFloat() else 0.003f
            val noise = (random.nextFloat() - 0.5f) * noiseScale + (0.004 * sin(2 * Math.PI * 90 * t)).toFloat()
            val voice = when {
                i < 38_400 -> speech[i % speech.size] * gain
                i >= 86_400 -> speech[(i - 86_400) % speech.size] * gain
                else -> 0f
            }
            voice + noise
        }
    }

    private fun speech(): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("hinglish-vad-speech.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            if (String(bytes, offset, 4, Charsets.US_ASCII) == "data") {
                return FloatArray(size / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("Missing WAV samples")
    }

    private fun withSource(asset: String = "pause-test.mp4", block: (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("noisy-pause-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
            block(source)
        } finally { source.delete() }
    }
}
