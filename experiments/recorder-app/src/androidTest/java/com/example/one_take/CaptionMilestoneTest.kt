package com.example.one_take

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionExporter
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.WhisperEngine
import com.example.one_take.features.CaptionFeatureStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses synthetic speech, never the user's recordings. Model installation is exercised through the marketplace. */
class CaptionMilestoneTest {
    @Test fun thirtySecondVideoProducesOfflineCaptionedCopy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val feature = CaptionFeatureStore.get(context)
        repeat(100) { if (!feature.installed) delay(100) }
        check(feature.installed) { "Install Offline Captions through the marketplace first" }
        val store = VideoStore(context.filesDir)
        val raw = store.createPendingRecording()
        val output = store.createPendingRecording()
        try {
            instrumentation.context.assets.open("caption-test.mp4").use { input ->
                raw.outputFile.outputStream().use { input.copyTo(it) }
            }
            raw.complete()
            val hash = sha256(raw.outputFile)
            val started = System.currentTimeMillis()
            feature.inUse = true
            val segments = try { WhisperEngine().transcribe(feature.modelFile, raw.outputFile) }
                finally { feature.inUse = false }
            val transcriptionMs = System.currentTimeMillis() - started
            println("CAPTION_TRANSCRIPTION_MS=$transcriptionMs")
            println("CAPTION_TRANSCRIPT=${segments.joinToString(" ") { it.text }}")
            assertTrue("Expected speech captions", segments.isNotEmpty())
            assertTrue("Expected recognizable speech", segments.any { it.text.contains("video", ignoreCase = true) || it.text.contains("caption", ignoreCase = true) })
            assertTrue(segments.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= 30_100 })
            val repository = CaptionRepository(context)
            repository.write(raw.outputFile, segments)
            assertEquals(segments, repository.read(raw.outputFile))
            val exportStarted = System.currentTimeMillis()
            CaptionExporter(context).export(raw.outputFile, segments, output.outputFile)
            output.complete()
            val exportMs = System.currentTimeMillis() - exportStarted
            println("CAPTION_EXPORT_MS=$exportMs")
            assertEquals("Original video must remain untouched", hash, sha256(raw.outputFile))
            assertTrue(output.outputFile.length() > 0)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.outputFile.absolutePath)
                assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 29_000)
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertNotNull(retriever.getFrameAtTime(5_000_000))
            } finally { retriever.release() }
            // Kept in cache only for the parent's visual verification, not in the user's gallery.
            output.outputFile.copyTo(File(context.cacheDir, "caption-verification.mp4"), overwrite = true)
            File(context.cacheDir, "caption-verification.txt").writeText("transcriptionMs=$transcriptionMs exportMs=$exportMs\n" + segments.joinToString("\n") { "${it.startMs}-${it.endMs}: ${it.text}" })
        } finally {
            raw.discard()
            output.discard()
            CaptionRepository(context).remove(raw.outputFile)
            store.deleteVideo(raw.outputFile)
            store.deleteVideo(output.outputFile)
        }
    }

    private fun sha256(file: File): List<Byte> = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
