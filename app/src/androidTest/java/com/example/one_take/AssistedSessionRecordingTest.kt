package com.example.one_take

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.LiveCaptureStore
import com.example.one_take.features.CaptionFeatureState
import com.example.one_take.features.CaptionFeatureStore
import com.onetake.engine.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real CameraX + microphone + Whisper take in Assisted Mode, with known speech played by the phone. */
class AssistedSessionRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fiveSecondAssistedTakeJournalsTranscriptAndSilenceUnderItsModeHeader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission")).use { it.readBytes() }
        }
        val features = CaptionFeatureStore.get(context)
        val jobs = CaptionJobs.get(context)
        val coordinator = LiveCaptureCoordinator.get(context)
        val captures = LiveCaptureStore(File(context.noBackupFilesDir, "capture_ledgers"))
        val projects = EngineProjectStore(context)
        val videos = VideoStore(context.filesDir)
        compose.waitUntil(15_000) { features.state !is CaptionFeatureState.Checking }
        assertTrue("Live transcript needs the installed Whisper model", features.installed)
        val wasEnabled = features.enabled
        val beforeFiles = videos.directory.listFiles().orEmpty().map { it.name }.toSet()
        val beforeSessions = captures.sessions().toSet()
        val speech = File(context.cacheDir, "assisted-session-speech.mp4")
        instrumentation.context.assets.open("caption-test.mp4").use { input -> speech.outputStream().use { input.copyTo(it) } }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val player = MediaPlayer()
        var session: String? = null
        try {
            compose.runOnIdle { features.setFeatureEnabled(true) }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Assisted Mode").performClick()
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithContentDescription("Start recording").assertIsEnabled() }.isSuccess }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 / 4, 0)
            player.setDataSource(speech.absolutePath)
            player.prepare()

            compose.onNodeWithContentDescription("Start recording").performClick()
            compose.waitUntil(15_000) {
                captures.sessions().firstOrNull { it !in beforeSessions && captures.snapshot(it).captureStarted }
                    ?.also { session = it } != null
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Stop recording").fetchSemanticsNodes().isNotEmpty() }
            // Speech, a two-second interior pause, then speech again: about five seconds in total.
            player.start()
            SystemClock.sleep(1_500)
            player.pause()
            player.seekTo(1_800)
            SystemClock.sleep(2_000)
            player.start()
            SystemClock.sleep(1_700)
            player.pause()
            compose.onNodeWithContentDescription("Stop recording").performClick()

            val id = checkNotNull(session)
            compose.waitUntil(45_000) { captures.snapshot(id).phase == SessionPhase.READY }
            val source = File(videos.directory, checkNotNull(captures.snapshot(id).captureSourceName))
            val finalized = recordingFingerprint(source)
            // Analysis hands off to ReviewScreen while the caption and pause job keeps running.
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Review video").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(120_000) { !jobs.busy }
            runBlocking { coordinator.flush() }

            val recorded = checkNotNull(projects.session(source)) { "Finalized take must be adopted with its session header" }
            assertEquals(id, recorded.sessionId)
            assertEquals(SessionMode.ASSISTED, recorded.header.mode)
            assertNull("Assisted Mode sessions carry no script", recorded.header.script)
            assertNotNull(recorded.header.startedAtEpochMs)
            assertEquals(SessionPhase.READY, recorded.phase)
            assertEquals("The original recording must stay byte-identical after analysis", finalized, recordingFingerprint(source))
            assertEquals(recorded.sourceId, finalized)

            val transcript = recorded.signals<TranscriptSegment>(ClockDomain.RECOGNIZER)
            assertTrue("Committed live transcript segments must be journaled", transcript.isNotEmpty())
            assertEquals("Each committed segment is journaled once", transcript.map { it.id }.distinct(), transcript.map { it.id })
            assertTrue(transcript.zipWithNext().all { (left, right) -> left.endSample <= right.startSample })
            assertTrue("No live Script Mode progress in Assisted Mode", recorded.scriptProgressHistory.isEmpty())

            val silence = recorded.signals<Silence>(ClockDomain.RECOGNIZER) + recorded.signals<Silence>(ClockDomain.MEDIA)
            assertTrue("The interior pause must be journaled as silence", silence.isNotEmpty())
        } finally {
            player.release()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { jobs.cancel() }
            compose.waitUntil(30_000) { !jobs.busy }
            runBlocking { coordinator.flush() }
            val sourceName = session?.let { id ->
                compose.waitUntil(30_000) { captures.snapshot(id).phase !in setOf(SessionPhase.RECORDING, SessionPhase.FINALIZING) }
                captures.snapshot(id).captureSourceName
            }
            // Only this take's original and its captioned export, never other gallery entries.
            val owned = listOfNotNull(sourceName?.let { File(videos.directory, it) }, jobs.exportedFile)
                .filter { it.name !in beforeFiles && it.parentFile == videos.directory }
            owned.forEach { file ->
                runBlocking { jobs.removeMetadata(file) }
                videos.deleteVideo(file)
            }
            instrumentation.runOnMainSync { features.setFeatureEnabled(wasEnabled) }
            speech.delete()
        }
    }
}
