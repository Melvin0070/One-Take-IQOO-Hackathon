package com.example.one_take

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.features.CaptionFeatureStore
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real microphone + CameraX + inference, using known speech played by the test phone. */
class LiveCaptionRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun thirtySecondRecordingBuildsCaptionsBeforeStopAndExportsAutomatically() {
        compose.onNodeWithText("Assisted Mode").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = VideoStore(context.filesDir)
        val before = store.directory.listFiles().orEmpty().map { it.name }.toSet()
        val feature = CaptionFeatureStore.get(context)
        val jobs = CaptionJobs.get(context)
        compose.waitUntil(15_000) { feature.installed }
        val wasEnabled = feature.enabled
        compose.runOnIdle { feature.setFeatureEnabled(true) }
        val speech = File(context.cacheDir, "live-caption-speech.mp4")
        instrumentation.context.assets.open("caption-test.mp4").use { input -> speech.outputStream().use { input.copyTo(it) } }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val player = MediaPlayer()
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 / 4, 0)
            player.setDataSource(speech.absolutePath)
            player.prepare()
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithContentDescription("Start recording").assertIsEnabled() }.isSuccess }
            compose.onNodeWithContentDescription("Start recording").performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription("Stop recording").assertIsEnabled() }.isSuccess }
            player.start()
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < 30_000) {
                compose.waitForIdle()
                SystemClock.sleep(100)
            }
            val active = jobs.javaClass.getDeclaredField("live").apply { isAccessible = true }.get(jobs)
                as? com.example.one_take.captions.LiveCaptionSession
            val pcm = active?.microphone?.snapshot()
            if (pcm != null) {
                val bytes = java.nio.ByteBuffer.allocate(pcm.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                pcm.forEach { bytes.putFloat(it) }
                File(context.cacheDir, "live-test-audio.f32").writeBytes(bytes.array())
            }
            val windowsBeforeStop = jobs.liveWindowCount
            val stopped = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription("Stop recording").performClick()
            compose.waitUntil(120_000) { jobs.operation == "export" || !jobs.busy }
            val captionDelay = SystemClock.elapsedRealtime() - stopped
            // The application-scoped export must survive a new Activity instance.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(120_000) { !jobs.busy }
            val stopDelay = SystemClock.elapsedRealtime() - stopped
            val source = jobs.sourcePath?.let(::File)
            val output = jobs.exportedFile
            File(context.cacheDir, "live-caption-result.txt").writeText(
                "windowsBeforeStop=$windowsBeforeStop\nusedLive=${jobs.usedLiveCaptions}\nstopToCaptionsMs=$captionDelay\nstopToExportMs=$stopDelay\nreferenceDecodeMs=${jobs.referenceDecodeMs}\nalignmentMs=${jobs.alignmentMs}\nrenderMs=${jobs.exportMs}\nmeasuredStopToCaptionsMs=${jobs.stopToCaptionsMs}\nerror=${jobs.error}\nfallback=${jobs.liveFallbackReason}\nsource=${source?.name}\noutput=${output?.name}\n")
            source?.copyTo(File(context.cacheDir, "live-caption-original.mp4"), overwrite = true)
            output?.copyTo(File(context.cacheDir, "live-caption-output.mp4"), overwrite = true)
            assertTrue("Inference must complete before Stop", windowsBeforeStop > 0)
            assertNull("Caption job failed", jobs.error)
            assertTrue("Live microphone must align with the recorded audio", jobs.usedLiveCaptions)
            assertNotNull("Original is preserved", source)
            assertNotNull("Captioned copy is exported automatically", output)
            val engineState = com.example.one_take.engine.EngineProjectStore(context).read(source!!)!!
            assertTrue("Capture UUID must survive finalization", engineState.captureStarted)
            val history = com.example.one_take.engine.LiveCaptureStore(
                File(context.noBackupFilesDir, "capture_ledgers")
            ).events(engineState.sessionId)
            assertTrue("Real live ASR must enter the capture history", history.any {
                it.change is com.onetake.engine.Change.ProvisionalTranscript &&
                    it.clock == com.onetake.engine.ClockDomain.RECOGNIZER
            })
            assertEquals(com.onetake.engine.SessionPhase.READY, engineState.phase)
            assertNull("Provisional text is cleared on finalization", engineState.provisionalText)
            val segments = CaptionRepository(context).read(source)!!
            assertTrue(segments.isNotEmpty())
            assertTrue("Recognizable known speech", segments.any { it.text.contains("caption", true) || it.text.contains("video", true) })
            File(context.cacheDir, "live-caption-result.txt").appendText(segments.joinToString("\n") { "${it.startMs}-${it.endMs}: ${it.text}" })
            val decision = com.example.one_take.editing.EditRepository(context).read(source)
            assertNotNull("Pause decisions must be preserved", decision)
            val editedDuration = decision!!.keptRanges().sumOf { it.endMs - it.startMs }
            for (file in listOf(source, output!!)) {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(file.absolutePath)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    if (file == source) assertTrue(duration >= 29_000)
                    else assertTrue("Export must match kept ranges: $duration vs $editedDuration",
                        kotlin.math.abs(duration - editedDuration) <= 500)
                    assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val frames = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    File(context.cacheDir, "live-caption-result.txt").appendText("\n${file.name}: ${width}x${height}, frames=$frames\n")
                    val frame = metadata.getFrameAtTime(5_000_000)
                    assertNotNull(frame)
                    if (file == output) {
                        File(context.cacheDir, "live-caption-output.png").outputStream().use {
                            frame!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    frame?.recycle()
                } finally { metadata.release() }
            }
            output.copyTo(File(context.cacheDir, "live-caption-output.mp4"), overwrite = true)
            source.copyTo(File(context.cacheDir, "live-caption-original.mp4"), overwrite = true)
        } finally {
            player.release()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
            compose.runOnIdle { jobs.cancel() }
            compose.waitUntil(15_000) { !jobs.busy }
            compose.runOnIdle { feature.setFeatureEnabled(wasEnabled) }
            // Only remove this job's identified files, never every new gallery entry.
            listOfNotNull(jobs.sourcePath?.let(::File), jobs.exportedFile)
                .distinctBy { it.absolutePath }
                .filter { it.name !in before && it.parentFile == store.directory }
                .forEach {
                    CaptionRepository(context).remove(it)
                    com.example.one_take.editing.EditRepository(context).remove(it)
                    com.example.one_take.vision.FaceTrackRepository(context).remove(it)
                    com.example.one_take.engine.EngineProjectStore(context).remove(it)
                    com.example.one_take.engine.LiveCaptureStore(
                        File(context.noBackupFilesDir, "capture_ledgers")
                    ).removeForSource(it.name)
                    store.deleteVideo(it)
                }
            speech.delete()
        }
    }
}
