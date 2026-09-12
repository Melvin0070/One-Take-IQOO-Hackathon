package com.example.one_take

import android.media.MediaMetadataRetriever
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.captions.CaptionPreset
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionStyleStore
import com.example.one_take.editing.EditCut
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditRepository
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises real review/undo/export with an owned fixture, never a user's recording. */
class EditFeatureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editsUndoPersistAndExportWithPresetWithoutChangingOriginal() = verifyEditedExport(true)

    @Test fun editsExportWithoutCaptionsWithoutChangingOriginal() = verifyEditedExport(false)

    private fun verifyEditedExport(withCaptions: Boolean) {
        compose.onNodeWithText("Assisted Mode").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = VideoStore(context.filesDir)
        val raw = store.createPendingRecording()
        val captions = CaptionRepository(context)
        val edits = EditRepository(context)
        val jobs = CaptionJobs.get(context)
        val style = CaptionStyleStore.get(context)
        val previousStyle = style.preset
        var exported: File? = null
        try {
            instrumentation.context.assets.open("caption-test.mp4").use { input ->
                raw.outputFile.outputStream().use { input.copyTo(it) }
            }
            raw.complete()
            val source = raw.outputFile
            val originalHash = hash(source)
            captions.write(source, if (withCaptions) listOf(CaptionSegment(0, 5_000, "Before the cut"),
                CaptionSegment(10_000, 15_000, "After the cut")) else emptyList())
            val sourceMetadata = MediaMetadataRetriever()
            val sourceDuration = try {
                sourceMetadata.setDataSource(source.absolutePath)
                sourceMetadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            } finally { sourceMetadata.release() }
            val decision = EditDecision(sourceDuration, listOf(EditCut("test-pause", 5_000, 10_000, "silence")))
            edits.write(source, decision)
            assertEquals(decision, edits.read(source))
            assertNotNull(com.example.one_take.engine.EngineProjectStore(context).read(source))
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Saved videos").assertExists() }.isSuccess
            }
            compose.onNodeWithContentDescription("Saved videos").performClick()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Play " + source.name).assertExists() }.isSuccess
            }
            compose.onNodeWithContentDescription("Play " + source.name).performClick()
            compose.waitUntil(15_000) {
                var playing = false
                compose.activityRule.scenario.onActivity { activity ->
                    playing = findPlayer(activity.findViewById(android.R.id.content))?.player?.isPlaying == true
                }
                playing
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithText("Edits").assertExists() }.isSuccess
            }
            assertEquals(decision, edits.read(source))
            compose.onNodeWithText("Edits").performClick()
            compose.onNodeWithText("Undo").performClick()
            compose.waitUntil(10_000) { edits.read(source)?.cuts?.singleOrNull()?.enabled == false }
            assertEquals(decision.restoreAll(), edits.read(source))
            val engineState = com.example.one_take.engine.EngineProjectStore(context).read(source)!!
            assertTrue(engineState.edits!!.cuts.none { it.enabled })
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) { edits.read(source)?.cuts?.singleOrNull()?.enabled == true }
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle { style.select(CaptionPreset.BOLD) }
            compose.onNodeWithText(if (withCaptions) "Save captioned copy" else "Save edited copy").performClick()
            compose.waitUntil(120_000) { !jobs.busy && (jobs.exportedFile != null || jobs.error != null) }
            assertNull(jobs.error)
            exported = jobs.exportedFile
            assertNotNull(exported)
            assertEquals(originalHash, hash(source))
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(exported!!.absolutePath)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Five-second cut must shorten the export: $duration", duration in 24_500..25_500)
                assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val frame = metadata.getFrameAtTime(6_000_000)!!
                File(context.cacheDir, "edit-feature-export-$withCaptions.png").outputStream().use {
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                frame.recycle()
            } finally { metadata.release() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.videos", exported!!)
            context.contentResolver.openInputStream(uri).use { input -> assertTrue(input!!.read() >= 0) }
            assertEquals("content", uri.scheme)
        } finally {
            File(context.cacheDir, "edit-test-ui.txt").writeText(
                runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("no tree") +
                "\nedits=" + edits.read(raw.outputFile).toString())
            compose.activityRule.scenario.onActivity { activity ->
                val player = findPlayer(activity.findViewById(android.R.id.content))?.player
                File(context.cacheDir, "edit-test-ui.txt").appendText(
                    "\nplayer=$player state=${player?.playbackState} ready=${player?.playWhenReady} error=${player?.playerError}")
            }
            compose.runOnIdle { jobs.cancel(); style.select(previousStyle) }
            compose.waitUntil(15_000) { !jobs.busy }
            // Release the review player before deleting its source media.
            compose.activityRule.scenario.onActivity { it.setContent {} }
            compose.waitForIdle()
            captions.remove(raw.outputFile)
            edits.remove(raw.outputFile)
            com.example.one_take.engine.EngineProjectStore(context).remove(raw.outputFile)
            raw.discard()
            store.deleteVideo(raw.outputFile)
            exported?.let {
                com.example.one_take.engine.EngineProjectStore(context).remove(it)
                store.deleteVideo(it)
            }
        }
    }

    private fun findPlayer(view: android.view.View): androidx.media3.ui.PlayerView? {
        if (view is androidx.media3.ui.PlayerView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16_384)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
