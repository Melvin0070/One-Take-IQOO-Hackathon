package com.example.one_take

import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.AudioDecoder
import com.example.one_take.captions.CaptionExporter
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.onetake.engine.android.whisper.QnnWhisperTranscriber
import com.onetake.engine.android.whisper.install.WhisperBundleInstaller
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.ExecutionPhase
import com.onetake.engine.inference.ExecutionReport
import com.onetake.engine.inference.ExecutionStatus
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before

/** Opt-in real-audio contract, using only the checked-in synthetic speech fixture. */
class QnnWhisperTranscriptionTest {
    @Before fun foregroundOptInRun() {
        if (InstrumentationRegistry.getArguments().getString("requireWhisperTranscription") == "true") {
            keepQnnTestInForeground()
        }
    }

    @Test fun recordedAudioProducesTimedTextOnHtp() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 31 && Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        assumeTrue(InstrumentationRegistry.getArguments().getString("requireWhisperTranscription") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File.createTempFile("qnn-transcription-", ".mp4", context.cacheDir)
        val output = File(context.cacheDir, fixture.nameWithoutExtension + "-captioned.mp4")
        val repository = CaptionRepository(context)
        try {
            instrumentation.context.assets.open("caption-test.mp4").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            val samples = AudioDecoder.decodeMono16k(fixture)
            val reports = mutableListOf<ExecutionReport>()
            var cancelOnDecoder = false
            var cancellationRequested = false
            QnnWhisperTranscriber.open(context,
                if (InstrumentationRegistry.getArguments().getString("requireWhisperInstall") == "true") {
                    checkNotNull(WhisperBundleInstaller.resolveInstalled(context)) { "Install the verified bundle first" }
                } else File(context.filesDir, "qnn-whisper-test"),
                listener = {
                    reports.add(it)
                    if (cancelOnDecoder && it.modelId.endsWith("-decoder") &&
                        it.phase == ExecutionPhase.EXECUTION && it.status == ExecutionStatus.SUCCEEDED) {
                        cancellationRequested = true
                    }
                }).use { transcriber ->
                assertThrows(CancellationException::class.java) {
                    transcriber.transcribe(samples) { true }
                }
                cancelOnDecoder = true
                assertThrows(CancellationException::class.java) {
                    transcriber.transcribe(samples) { cancellationRequested }
                }
                assertTrue("Cancellation must follow actual decoder execution", cancellationRequested)
                cancelOnDecoder = false
                cancellationRequested = false
                val reportStart = reports.size
                val started = System.nanoTime()
                val result = transcriber.transcribe(samples)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("Expected recognizable caption text", result.any {
                    it.text.contains("video", ignoreCase = true) || it.text.contains("caption", ignoreCase = true)
                })
                val duration = samples.size * 1000L / 16000
                assertTrue("Expected bounded model timestamps", result.all {
                    it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= duration && it.text.isNotBlank()
                })
                assertTrue("Expected ordered captions", result.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
                for (graph in listOf("encoder", "decoder")) {
                    assertTrue("Expected actual $graph NPU execution", reports.any {
                        it.modelId == "qualcomm-whisper-tiny-v061-$graph" &&
                            it.phase == ExecutionPhase.EXECUTION && it.status == ExecutionStatus.SUCCEEDED &&
                            it.actualBackend == BackendKind.NPU && it.policy == BackendPolicy.NPU_REQUIRED
                    })
                }
                val captions = result.map { CaptionSegment(it.startMs, it.endMs, it.text) }
                repository.write(fixture, captions)
                assertEquals(captions, repository.read(fixture))
                CaptionExporter(context).export(fixture, captions, output)
                assertTrue("Expected exported captioned video", output.length() > 0)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    val frame = retriever.getFrameAtTime(5_000_000)
                    assertNotNull(frame)
                    try {
                        File(context.cacheDir, "qnn-caption-frame.png").outputStream().use {
                            frame!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { frame?.recycle() }
                } finally { retriever.release() }
                File(context.cacheDir, "qnn-transcription-result.txt").writeText(
                    "elapsedMs=$elapsedMs\n" +
                        reports.drop(reportStart).filter { it.phase == ExecutionPhase.EXECUTION }
                            .groupBy { it.modelId }.entries.joinToString("\n", postfix = "\n") { (id, calls) ->
                                "$id calls=${calls.size} totalMs=${calls.sumOf { it.durationNanos } / 1_000_000}"
                            } + result.joinToString("\n") { "${it.startMs}-${it.endMs}: ${it.text}" }
                )
            }
        } finally {
            repository.remove(fixture)
            output.delete()
            fixture.delete()
        }
    }
}
