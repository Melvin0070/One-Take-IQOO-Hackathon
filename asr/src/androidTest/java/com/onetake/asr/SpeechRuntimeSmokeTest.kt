package com.onetake.asr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpeechProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Checking offline speech recognition" })
    }
}

class SpeechRuntimeSmokeTest {
    @Test fun pinnedRuntimeRecognizesRecordedSpeechAndDetectsVoice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("requireSpeech") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, SpeechProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val directory = File(context.filesDir, "speech-test")
            val samples = wavSamples(instrumentation.context.assets.open("room-confirmation.wav").use { it.readBytes() })
            assertTrue(samples.any { it != 0f })
            val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = File(directory, "silero_vad.onnx").path, maxSpeechDuration = 20f)))
            try {
                var segments = 0
                for (chunk in samples.asList().chunked(512)) {
                    vad.acceptWaveform(chunk.toFloatArray())
                    while (!vad.empty()) {
                        assertTrue(vad.front().samples.isNotEmpty())
                        segments++
                        vad.pop()
                    }
                }
                vad.flush()
                while (!vad.empty()) { segments++; vad.pop() }
                assertTrue("Silero must identify speech in recorded audio", segments > 0)
            } finally { vad.release() }
            val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(directory, "encoder-epoch-99-avg-1.int8.onnx").path,
                        decoder = File(directory, "decoder-epoch-99-avg-1.onnx").path,
                        joiner = File(directory, "joiner-epoch-99-avg-1.onnx").path),
                    tokens = File(directory, "tokens.txt").path,
                    provider = "cpu", numThreads = 1), enableEndpoint = false))
            try {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    assertTrue("Streaming recognition must produce actual text on SM8850", result.text.isNotBlank())
                    assertTrue(result.tokens.isNotEmpty())
                    File(context.cacheDir, "speech-runtime-smoke.txt").writeText(
                        "runtime=1.13.8\nbackend=CPU\nnonemptyRecognition=true\ntokenCount=${result.tokens.size}\n")
                } finally { stream.release() }
            } finally { recognizer.release() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun wavSamples(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4)
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            if (name == "fmt ") {
                require(length >= 16)
                require(buffer.getShort(offset + 8).toInt() == 1)
                require(buffer.getShort(offset + 10).toInt() == 1)
                require(buffer.getInt(offset + 12) == 16000)
                require(buffer.getShort(offset + 22).toInt() == 16)
                validFormat = true
            }
            if (name == "data") {
                require(validFormat && length % 2 == 0)
                return FloatArray(length / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + length + (length % 2)
        }
        error("WAV data chunk missing")
    }
}
