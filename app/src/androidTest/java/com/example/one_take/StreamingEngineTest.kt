package com.example.one_take

import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.AudioDecoder
import com.example.one_take.captions.WhisperEngine
import com.example.one_take.features.CaptionFeatureStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingEngineTest {
    @Test fun shortWindowsProduceRecognizableSpeech() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val feature = CaptionFeatureStore.get(context)
        repeat(100) { if (!feature.installed) delay(100) }
        check(feature.installed)
        val fixture = File(context.cacheDir, "streaming-fixture.mp4")
        instrumentation.context.assets.open("caption-test.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        try {
            val audio = AudioDecoder.decodeMono16k(fixture)
            val report = File(context.cacheDir, "streaming-engine-result.txt")
            report.writeText("")
            for (optimized in listOf(false, true)) {
                WhisperEngine().withSession(feature.modelFile, optimizeForStreaming = optimized) { session ->
                    for (start in listOf(0, 4, 8)) {
                        val began = System.currentTimeMillis()
                        val captions = session.transcribe(audio.copyOfRange(start * 16000, (start + 6) * 16000))
                        report.appendText("optimized=$optimized start=$start ms=${System.currentTimeMillis()-began}: ${captions.joinToString(" "){it.text}}\n")
                        assertTrue("Short window must transcribe", captions.isNotEmpty())
                    }
                }
            }
        } finally { fixture.delete() }
    }
}
