package com.example.one_take

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecorderFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val videoStore by lazy { VideoStore(targetContext.filesDir) }
    private var preExistingVideoArtifacts: Set<String> = emptySet()

    @Before
    fun setUp() {
        preExistingVideoArtifacts = videoStore.directory.listFiles()
            ?.mapTo(mutableSetOf(), File::getAbsolutePath)
            .orEmpty()
        grantRuntimePermission(Manifest.permission.CAMERA)
        grantRuntimePermission(Manifest.permission.RECORD_AUDIO)

        // The activity initially renders the permission gate before the shell grants above
        // complete. Recreating it makes the test start from the real camera screen.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Assisted Mode").performClick()
    }

    @After
    fun tearDown() {
        // Stop any active capture before removing only files created by this test.
        runCatching {
            // I2501 can freeze background instrumentation during asynchronous cleanup.
            // Dispose capture in the foreground, as VisualSuggestionsTest already does.
            compose.runOnUiThread { compose.activity.setContent {} }
        }
        waitForNewPendingMarkersToClear()
        videoStore.directory.listFiles()
            ?.filter { it.absolutePath !in preExistingVideoArtifacts && it.extension.equals("mp4", ignoreCase = true) }
            ?.forEach { videoStore.deleteVideo(it) }
    }

    @Test
    fun savedVideosCanBeOpenedFromCamera() {
        waitForEnabledContentDescription(START_RECORDING)

        compose.onNodeWithContentDescription(SAVED_VIDEOS).performClick()
        waitForText(SAVED_VIDEOS)
        compose.onNodeWithText(RECORD_A_VIDEO).assertIsDisplayed()

        compose.onNodeWithText(RECORD_A_VIDEO).performClick()
        waitForEnabledContentDescription(START_RECORDING)
    }

    @Test
    fun recordingSurvivesLifecycleRecreationBackAndPlayback() {
        val video = recordVideo()

        // Review state and the file must survive the Activity leaving and re-entering the
        // foreground, then a full Activity recreation.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        waitForText(REVIEW_VIDEO)
        check(video.exists()) { "Recording disappeared during lifecycle recreation" }

        // Back from review keeps the file and takes the user to the saved-video library.
        pressBack()
        waitForText(SAVED_VIDEOS)
        waitForContentDescription(PLAY_PREFIX + video.name)
        check(video.exists()) { "Recording disappeared when leaving review" }

        compose.onNodeWithContentDescription(PLAY_PREFIX + video.name).performClick()
        waitForText(REVIEW_VIDEO)
        waitForText(DONE)
        waitForVideoPlaying()
        compose.onNodeWithText(RETAKE).assertDoesNotExist()
        compose.onNodeWithText(PLAYBACK_ERROR).assertDoesNotExist()

        compose.onNodeWithText(DONE).performClick()
        waitForText(SAVED_VIDEOS)
        waitForContentDescription(PLAY_PREFIX + video.name)
    }

    @Test
    fun recordingFinalizesWhenActivityStopsDuringCapture() {
        waitForEnabledContentDescription(START_RECORDING)
        val pathsBeforeRecording = videoFiles().mapTo(mutableSetOf(), File::getAbsolutePath)

        compose.onNodeWithContentDescription(START_RECORDING).performClick()
        waitForContentDescription(STOP_RECORDING)
        Thread.sleep(RECORDING_DURATION_MILLIS)

        // ON_STOP must finalize the active CameraX recording instead of leaving an
        // untracked pending output behind.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var finalizedVideo: File? = null
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
            finalizedVideo = videoFiles().firstOrNull {
                it.absolutePath !in pathsBeforeRecording && it.length() > 0L
            }
            finalizedVideo != null
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        waitForText(REVIEW_VIDEO)
        val video = checkNotNull(finalizedVideo) { "Interrupted recording was not finalized" }
        assertPlayableVideo(video)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        waitForText(REVIEW_VIDEO)
        pressBack()
        waitForText(SAVED_VIDEOS)
        waitForContentDescription(PLAY_PREFIX + video.name)
    }

    @Test
    fun retakeCanBeCancelledOrConfirmed() {
        val video = recordVideo()
        waitForFileReady(video)

        compose.onNodeWithText(RETAKE).assertIsEnabled().performClick()
        waitForText(DELETE_RECORDING)
        compose.onNodeWithText(CANCEL).performClick()
        waitForText(REVIEW_VIDEO)
        check(video.exists()) { "Cancel removed the recording" }

        compose.onNodeWithText(RETAKE).performClick()
        waitForText(DELETE_RECORDING)
        clickDialogButton(DELETE)
        waitForEnabledContentDescription(START_RECORDING)
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) { !video.exists() }
        check(!video.exists()) { "Confirming Retake did not delete the recording" }
    }

    @Test
    fun keptVideoAppearsInLibraryAndCanBeDeleted() {
        val video = recordVideo()
        waitForFileReady(video)

        compose.onNodeWithText(KEEP_VIDEO).performClick()
        waitForText(SAVED_VIDEOS)
        waitForContentDescription(PLAY_PREFIX + video.name)

        compose.onNodeWithContentDescription(DELETE_PREFIX + video.name).performClick()
        waitForText(DELETE_RECORDING)
        compose.onNodeWithText(CANCEL).performClick()
        waitForContentDescription(PLAY_PREFIX + video.name)
        check(video.exists()) { "Cancel removed the saved recording" }

        compose.onNodeWithContentDescription(DELETE_PREFIX + video.name).performClick()
        waitForText(DELETE_RECORDING)
        clickDialogButton(DELETE)
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) { !video.exists() }
        check(!video.exists()) { "Deleting a saved video did not remove its file" }
        compose.onNodeWithContentDescription(PLAY_PREFIX + video.name).assertDoesNotExist()
    }

    @Test
    fun libraryRefreshesWhenInterruptedRecordingBecomesAvailable() {
        val video = recordVideo()
        val pending = videoStore.createPendingRecording()
        try {
            video.copyTo(pending.outputFile)
            compose.onNodeWithText(KEEP_VIDEO).performClick()
            waitForText(SAVED_VIDEOS)
            waitForContentDescription(PLAY_PREFIX + video.name)
            compose.onNodeWithContentDescription(PLAY_PREFIX + pending.outputFile.name).assertDoesNotExist()
            pending.preserveForRecovery()
            waitForContentDescription(PLAY_PREFIX + pending.outputFile.name)
        } finally {
            pending.discard()
            videoStore.deleteVideo(pending.outputFile)
        }
    }

    private fun recordVideo(): File {
        waitForEnabledContentDescription(START_RECORDING)
        val pathsBeforeRecording = videoFiles().mapTo(mutableSetOf(), File::getAbsolutePath)

        compose.onNodeWithContentDescription(START_RECORDING).performClick()
        waitForContentDescription(STOP_RECORDING)
        compose.onNodeWithContentDescription(SAVED_VIDEOS).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Flip").assertIsNotEnabled()

        // A short real capture gives CameraX time to write an encoded, playable file.
        Thread.sleep(RECORDING_DURATION_MILLIS)
        compose.onNodeWithContentDescription(STOP_RECORDING).performClick()
        waitForText(REVIEW_VIDEO)

        var createdVideo: File? = null
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
            createdVideo = videoFiles().firstOrNull {
                it.absolutePath !in pathsBeforeRecording && it.length() > 0L
            }
            createdVideo != null
        }
        return checkNotNull(createdVideo) { "Camera did not create a playable video file" }
            .also(::assertPlayableVideo)
    }

    private fun waitForFileReady(file: File) {
        // Retake/deletion intentionally reject a recording while its analysis/export owns it.
        val jobs = com.example.one_take.captions.CaptionJobs.get(targetContext)
        compose.waitUntil(timeoutMillis = 60_000) { !jobs.busy || jobs.sourcePath != file.absolutePath }
    }

    private fun clickDialogButton(label: String) {
        compose.onNode(
            hasText(label) and hasAnyAncestor(isDialog()),
            useUnmergedTree = true
        ).performClick()
    }

    private fun assertPlayableVideo(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMetadata = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            val durationMillis = if (durationMetadata == null) {
                0L
            } else {
                durationMetadata.toLongOrNull() ?: 0L
            }
            val widthMetadata = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )
            val width = if (widthMetadata == null) 0 else widthMetadata.toIntOrNull() ?: 0
            val heightMetadata = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )
            val height = if (heightMetadata == null) 0 else heightMetadata.toIntOrNull() ?: 0
            check(durationMillis > 0L) { "Recording has no playable duration: ${file.name}" }
            check(width > 0 && height > 0) {
                "Recording has no playable video dimensions: ${file.name}"
            }
        } finally {
            retriever.release()
        }
    }

    private fun waitForVideoPlaying() {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            var isPlaying = false
            compose.activityRule.scenario.onActivity { activity ->
                isPlaying = findVideoView(activity.window.decorView)?.isPlaying == true
            }
            isPlaying
        }
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun grantRuntimePermission(permission: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation
                .executeShellCommand("pm grant ${targetContext.packageName} $permission")
        ).use { it.readBytes() }
    }

    private fun videoFiles(): List<File> {
        return File(targetContext.filesDir, "videos")
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .orEmpty()
    }

    private fun waitForNewPendingMarkersToClear() {
        val deadline = SystemClock.uptimeMillis() + FILE_WAIT_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val hasNewPendingMarker = videoStore.directory.listFiles().orEmpty()
                .any { it.name.endsWith(".pending") && it.absolutePath !in preExistingVideoArtifacts }
            if (!hasNewPendingMarker) return
            SystemClock.sleep(100L)
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForContentDescription(description: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForEnabledContentDescription(description: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            runCatching {
                compose.onNodeWithContentDescription(description).assertIsEnabled()
            }.isSuccess
        }
    }

    private companion object {
        const val START_RECORDING = "Start recording"
        const val STOP_RECORDING = "Stop recording"
        const val SAVED_VIDEOS = "Projects"
        const val RECORD_A_VIDEO = "Record a video"
        const val REVIEW_VIDEO = "Review video"
        const val KEEP_VIDEO = "Keep video"
        const val RETAKE = "Retake"
        const val DONE = "Done"
        const val CANCEL = "Cancel"
        const val DELETE = "Delete"
        const val DELETE_RECORDING = "Delete recording?"
        const val PLAYBACK_ERROR = "This recording cannot be played on this device."
        const val PLAY_PREFIX = "Play "
        const val DELETE_PREFIX = "Delete "
        const val RECORDING_DURATION_MILLIS = 2_000L
        const val UI_WAIT_TIMEOUT = 20_000L
        const val FILE_WAIT_TIMEOUT = 20_000L
    }
}
