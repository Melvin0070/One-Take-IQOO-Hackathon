package com.example.one_take

import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.audio.SileroSpeechClassifier
import com.example.one_take.captions.AudioDecoder
import com.onetake.engine.SpeechActivity
import com.onetake.engine.StreamingPauseDetector
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SileroSpeechClassifierTest {
    @Test fun nativeNeuralDetectorFindsPausesInTheRecordedRoomFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("silero-regression-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("room-recording-regression.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val pcm = runBlocking { AudioDecoder.decodeMono16k(source) }
            SileroSpeechClassifier.create(instrumentation.targetContext).use { classifier ->
                val detector = StreamingPauseDetector(classifier)
                val cuts = pcm.asList().chunked(1_003).flatMap { detector.append(it.toFloatArray()) }
                assertTrue("The native neural path must detect both recorded pauses: $cuts", cuts.size >= 2)
                assertTrue(cuts.any { it.startSample >= 2_000 * 16 && it.endSample <= 5_300 * 16 && it.endSample - it.startSample >= 800 * 16 })
                assertTrue(cuts.any { it.startSample >= 6_200 * 16 && it.endSample <= 9_350 * 16 && it.endSample - it.startSample >= 800 * 16 })
            }
        } finally { source.delete() }
    }

    @Test fun invalidAndClosedNeuralFramesNeverBecomeSilence() {
        val classifier = SileroSpeechClassifier.create(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            assertEquals(SpeechActivity.UNKNOWN, classifier.classify(FloatArray(320)))
            assertEquals(SpeechActivity.UNKNOWN, classifier.classify(FloatArray(512) { Float.NaN }))
        } finally { classifier.close() }
        assertEquals(SpeechActivity.UNKNOWN, classifier.classify(FloatArray(512)))
        classifier.close()
    }
}
