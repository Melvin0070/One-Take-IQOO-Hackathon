package com.example.one_take

import android.media.MediaMetadataRetriever
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.*
import com.example.one_take.editing.*
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.toApp
import com.example.one_take.features.CaptionFeatureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Controlled review metadata validates wiring; native recognition is checked separately. */
class SpeechSuggestionFeatureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeRecognitionReturnsBoundedWordEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File.createTempFile("word-evidence-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("room-pause-test.mp4").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            val segments = WhisperEngine().transcribe(CaptionFeatureStore.get(context).modelFile, fixture)
            assertTrue("Native inference must retain model-timed words", segments.sumOf { it.words.size } >= 3)
            segments.forEach { segment -> segment.words.forEach { word ->
                assertTrue(word.startMs >= segment.startMs && word.endMs <= segment.endMs)
                assertTrue(word.endMs > word.startMs)
                assertTrue(word.confidence.isFinite() && word.confidence in 0f..1f)
            } }
            File(context.cacheDir, "speech-word-evidence.txt").writeText(segments.joinToString("\n"))
        } finally { fixture.delete() }
    }

    @Test fun disabledSuggestionPreviewApplyUndoReopenAndExportPreserveOriginal() {
        compose.onNodeWithText("Assisted Mode").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videos = VideoStore(context.filesDir)
        val pending = videos.createPendingRecording()
        val source = pending.outputFile
        val projects = EngineProjectStore(context)
        val jobs = CaptionJobs.get(context)
        var exported: File? = null
        try {
            instrumentation.context.assets.open("room-pause-test.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            pending.complete()
            val original = hash(source)
            val duration = duration(source)
            projects.saveCaptions(source, listOf(CaptionSegment(0, 2_000, "um hello", listOf(
                CaptionWord(200, 700, "um", .95f), CaptionWord(800, 1_700, "hello", .95f),
            ))))
            projects.saveEdits(source, EditDecision(duration, emptyList()))
            val suggestion = projects.read(source)!!.edits!!.cuts.single()
            assertFalse(suggestion.enabled)
            compose.waitUntil(15_000) { runCatching {
                compose.onNodeWithContentDescription("Projects").assertExists()
            }.isSuccess }
            compose.onNodeWithContentDescription("Projects").performClick()
            compose.waitUntil(15_000) { runCatching {
                compose.onNodeWithContentDescription("Play " + source.name).assertExists()
            }.isSuccess }
            compose.onNodeWithContentDescription("Play " + source.name).performClick()
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Edits").assertExists() }.isSuccess }
            compose.onNodeWithText("Edits").performClick()
            compose.onNodeWithText("Preview").performClick()
            compose.onNodeWithText("Preview original").assertExists()
            compose.onNodeWithText("Close preview").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) { projects.read(source)!!.edits!!.cuts.single().enabled }
            compose.onNodeWithText("Undo").performClick()
            compose.waitUntil(10_000) { !projects.read(source)!!.edits!!.cuts.single().enabled }
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) { projects.read(source)!!.edits!!.cuts.single().enabled }
            compose.onNodeWithText("Close").performClick()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Edits").assertExists() }.isSuccess }
            assertTrue(projects.read(source)!!.edits!!.cuts.single().enabled)
            val mapped = projects.read(source)!!.edits!!.mapCaptions(projects.read(source)!!.captions!!)
            assertTrue("Removed filler must not remain in mapped captions", mapped.none { it.text.contains("um") })
            compose.onNodeWithText("Save captioned copy").performClick()
            compose.waitUntil(120_000) { !jobs.busy && (jobs.exportedFile != null || jobs.error != null) }
            assertNull(jobs.error)
            exported = jobs.exportedFile
            assertNotNull(exported)
            val expected = duration - (suggestion.endSample - suggestion.startSample) / 16
            assertTrue("Export must follow applied suggestion", kotlin.math.abs(duration(exported!!) - expected) < 250)
            assertEquals(original, hash(source))
            File(context.cacheDir, "speech-suggestion-result.txt").writeText(
                "preview/apply/undo/reopen/export passed\noriginalPreserved=true\ncut=$suggestion\n")
        } finally {
            compose.runOnIdle { jobs.cancel() }
            compose.waitUntil(15_000) { !jobs.busy }
            compose.activityRule.scenario.onActivity { it.setContent {} }
            compose.waitForIdle()
            CaptionRepository(context).remove(source)
            EditRepository(context).remove(source)
            projects.remove(source)
            pending.discard()
            videos.deleteVideo(source)
            exported?.let { projects.remove(it); videos.deleteVideo(it) }
        }
    }

    private fun duration(file: File): Long = MediaMetadataRetriever().let { metadata ->
        try { metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally { metadata.release() }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
