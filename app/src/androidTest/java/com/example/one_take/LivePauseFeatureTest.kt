package com.example.one_take

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.AudioDecoder
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.editing.EditRepository
import com.example.one_take.editing.LivePauseReconciler
import com.example.one_take.editing.analyzePauses
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.LiveCaptureStore
import com.example.one_take.features.CaptionFeatureState
import com.example.one_take.features.CaptionFeatureStore
import com.example.one_take.engine.toApp
import com.onetake.engine.Change
import com.onetake.engine.EngineState
import com.onetake.engine.SessionPhase
import com.example.one_take.audio.SpeechPauseDetector
import java.io.File
import java.security.MessageDigest
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device coverage for capture-time pause candidates and reversible edit promotion. */
@RunWith(AndroidJUnit4::class)
class LivePauseFeatureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context by lazy { instrumentation.targetContext }
    private val videoStore by lazy { VideoStore(context.filesDir) }
    private val captureStore by lazy {
        LiveCaptureStore(File(context.noBackupFilesDir, "capture_ledgers"))
    }
    private val coordinator by lazy { LiveCaptureCoordinator.get(context) }
    private val projects by lazy { EngineProjectStore(context) }
    private val captions by lazy { CaptionRepository(context) }
    private val edits by lazy { EditRepository(context) }
    private val jobs by lazy { CaptionJobs.get(context) }
    private val features by lazy { CaptionFeatureStore.get(context) }

    private var originalCaptionEnabled = false
    private var hadInstalledCaptionModel = false
    private val ownedSessions = linkedSetOf<String>()
    private val ownedSources = linkedSetOf<File>()

    @Before
    fun setUp() {
        grantRuntimePermission(Manifest.permission.CAMERA)
        grantRuntimePermission(Manifest.permission.RECORD_AUDIO)
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            features.state !is CaptionFeatureState.Checking
        }
        hadInstalledCaptionModel = features.installed
        originalCaptionEnabled = features.enabled
        if (hadInstalledCaptionModel) {
            compose.runOnIdle { features.setFeatureEnabled(false) }
        }
        compose.activityRule.scenario.recreate()
        waitForEnabledContentDescription(START_RECORDING)
    }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) }
        runCatching { instrumentation.runOnMainSync { jobs.cancel() } }
        runCatching { compose.waitUntil(UI_WAIT_TIMEOUT) { !jobs.busy } }
        runCatching { runBlocking { coordinator.flush() } }

        ownedSessions.forEach { session ->
            val deadline = SystemClock.uptimeMillis() + FILE_WAIT_TIMEOUT
            while (SystemClock.uptimeMillis() < deadline && runCatching {
                captureStore.snapshot(session).phase in setOf(SessionPhase.RECORDING, SessionPhase.FINALIZING)
            }.getOrDefault(false)) SystemClock.sleep(100L)
            runCatching { runBlocking { coordinator.flush() } }
            val state = runCatching { captureStore.snapshot(session) }.getOrNull()
            state?.captureSourceName?.let { ownedSources += File(videoStore.directory, it) }
        }
        ownedSources.forEach(::cleanupSource)
        ownedSessions.forEach { session ->
            // A journal can be unreadable after an intentionally failed test.  This is
            // the exact UUID allocated by this test, so the fallback remains scoped.
            captureLedger(session).delete()
        }
        if (hadInstalledCaptionModel) {
            instrumentation.runOnMainSync { features.setFeatureEnabled(originalCaptionEnabled) }
        }
    }

    @Test
    fun disabledCaptionsStillFinishPauseAnalysisAfterRealCapture() {
        val sourceAudio = copyAssetToCache("pause-real-audio", "room-pause-test.mp4")
        val player = MediaPlayer()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var session: String? = null
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 / 4, 0)
            player.setDataSource(sourceAudio.absolutePath)
            player.prepare()

            val beforeSessions = captureStore.sessions().toSet()
            compose.onNodeWithContentDescription(START_RECORDING).performClick()
            session = awaitNewStartedSession(beforeSessions)
            val sourceName = checkNotNull(captureStore.snapshot(session).captureSourceName)
            ownedSources += File(videoStore.directory, sourceName)
            waitForContentDescription(STOP_RECORDING)

            player.start()
            SystemClock.sleep(1_500L)
            player.pause()
            SystemClock.sleep(3_000L)
            player.start()
            waitForPlayerToFinish(player)
            compose.onNodeWithContentDescription(STOP_RECORDING).performClick()
            waitForText(REVIEW_VIDEO)

            val state = awaitState(session) { it.phase == SessionPhase.READY }
            val source = File(videoStore.directory, checkNotNull(state.captureSourceName))
            val originalHash = hash(source)
            waitForPauseJob(source)

            assertFalse("Disabled captions must not use Whisper", jobs.usedLiveCaptions)
            assertEquals("No recognition windows should run", 0, jobs.liveWindowCount)
            assertNull("Pause job must complete successfully", jobs.error)
            assertTrue(
                "Disabled captions must not create caption segments",
                runBlocking { jobs.read(source).isNullOrEmpty() },
            )
            assertNotNull(
                "The saved take still receives pause analysis",
                runBlocking { jobs.readEdits(source) },
            )
            assertPlayableWithAudioAndVideo(source)
            assertEquals("The project must retain the durable capture UUID", session, projects.read(source)?.sessionId)
            assertEquals("Pause analysis must not modify the raw video", originalHash, hash(source))

            val history = runBlocking { coordinator.history(session) }
            assertTrue(history.any { it.change is Change.SourceFinalized })
            val candidateCount = history.count { it.change is Change.PauseCandidateObserved }
            val savedCuts = checkNotNull(projects.read(source)?.edits).cuts
            val recordedPcm = runBlocking { AudioDecoder.decodeMono16k(source) }
            val activityCounts = com.example.one_take.audio.WebRtcSpeechClassifier.create().use { classifier ->
                (0 until recordedPcm.size - 319 step 320).map {
                    classifier.classify(recordedPcm.copyOfRange(it, it + 320))
                }.groupingBy { it }.eachCount()
            }
            val neuralCounts = com.example.one_take.audio.SileroSpeechClassifier.create(context).use { classifier ->
                (0 until recordedPcm.size - 511 step 512).map {
                    classifier.classify(recordedPcm.copyOfRange(it, it + 512))
                }.groupingBy { it }.eachCount()
            }
            File(context.cacheDir, "live-pause-recorded-diagnostics.txt").writeText(
                "webrtcFrames=$activityCounts\nneuralFrames=$neuralCounts\npeak=${recordedPcm.maxOfOrNull { kotlin.math.abs(it) }}\n" +
                    "liveCandidates=${projects.read(source)?.pauseCandidates}\n" +
                    "savedAnalysis=${analyzePauses(source, recordedPcm, context)}\n"
            )
            File(context.cacheDir, "live-pause-real-result.txt").writeText(
                "candidates=$candidateCount\ncuts=${savedCuts.size}\nliveCuts=${savedCuts.count { it.reason == "silence (detected live)" }}\nwindows=${jobs.liveWindowCount}\nsource=${source.name}\n"
            )
            println("REAL_CAPTURE_PAUSE_CANDIDATES=$candidateCount")
            assertTrue("The controlled room recording must produce a live pause candidate", candidateCount > 0)
            assertTrue("A live pause must survive saved-audio confirmation", savedCuts.any {
                it.reason == "silence (detected live)"
            })
        } finally {
            player.release()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            session?.let { ownedSessions += it }
            sourceAudio.delete()
        }
    }

    @Test
    fun streamedPauseCandidateIsDurableThenReviewUndoApplyAndExportRemainReversible() {
        val pending = videoStore.createPendingRecording()
        val source = pending.outputFile
        ownedSources += source
        instrumentation.context.assets.open(PAUSE_FIXTURE).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        pending.complete()
        val originalHash = hash(source)
        val session = runBlocking { coordinator.begin(source) }
        ownedSessions += session
        coordinator.started(session)
        runBlocking { coordinator.flush() }

        val reference = runBlocking { AudioDecoder.decodeMono16k(source) }
        assertTrue("Pause fixture must contain enough audio for the interior silence", reference.size >= 6 * SAMPLE_RATE)
        val detector = SpeechPauseDetector.create(context)
        // The detector accepts arbitrary chunk boundaries. Feed the same deterministic
        // PCM in uneven pieces so this test covers its streaming state rather than a batch shortcut.
        val detected = ArrayList<com.onetake.engine.PauseCandidate>()
        var offset = 0
        val chunkSizes = listOf(137, 2_003, 511, 8_000, 31_999, 97)
        var chunkIndex = 0
        val pcm = reference
        detector.use {
            while (offset < pcm.size) {
                val end = min(pcm.size, offset + chunkSizes[chunkIndex % chunkSizes.size])
                detected += it.append(pcm.copyOfRange(offset, end))
                offset = end
                chunkIndex++
            }
        }
        assertTrue("The known PCM fixture must close an interior pause", detected.isNotEmpty())
        // Simulate the microphone's final read arriving after StopRequested.  The finalization
        // barrier must serialize this delayed snapshot before SourceFinalized adopts the source.
        coordinator.stop(session, "test_stop")
        runBlocking {
            coordinator.flush()
            coordinator.drainPauseCandidates(session, detected)
        }

        val beforeFinalize = runBlocking { coordinator.snapshot(session) }
        assertEquals(SessionPhase.FINALIZING, beforeFinalize.phase)
        assertNull("Candidates must not become edits before media is finalized", beforeFinalize.edits)
        assertEquals(detected.map { it.id }, beforeFinalize.pauseCandidates.map { it.id })
        assertTrue(
            "Candidates must be durable before finalization",
            runBlocking { coordinator.history(session) }.any { it.change is Change.PauseCandidateObserved },
        )

        runBlocking { coordinator.finalized(session, source) }
        val ready = checkNotNull(projects.read(source))
        assertEquals(session, ready.sessionId)
        assertEquals(SessionPhase.READY, ready.phase)
        assertEquals(detected.map { it.id }, ready.pauseCandidates.map { it.id })
        assertNull("Finalization alone must not invent an edit decision", ready.edits)

        val confirmed = analyzePauses(source, reference, context)
        assertTrue("The synthetic fixture must have a valid confirmed media decision", confirmed.durationMs > 0L)
        val reconciled = LivePauseReconciler.reconcile(detected, offsetMs = 0L, confirmed = confirmed)
        assertTrue("A live candidate must be promoted only inside confirmed silence", reconciled.cuts.isNotEmpty())
        assertTrue(reconciled.cuts.all { it.reason.contains("silence") })

        // The audio decoder and the container can round at different boundaries. Normalize the
        // decision to the engine's finalized sample duration before writing it to the project.
        val projectDurationMs = com.example.one_take.engine.recordingTimeline
            .msFromSamples(ready.durationSamples)
        val persisted = reconciled.copy(durationMs = projectDurationMs)
        projects.saveEdits(source, persisted)
        assertEquals(persisted, edits.read(source))
        assertEquals(persisted, projects.read(source)?.edits?.toApp())

        compose.onNodeWithContentDescription(SAVED_VIDEOS).performClick()
        waitForText(SAVED_VIDEOS)
        waitForContentDescription("Play ${source.name}")
        compose.onNodeWithContentDescription("Play ${source.name}").performClick()
        waitForText(REVIEW_VIDEO)
        waitForText("Edits")
        compose.onNodeWithText("Edits").performClick()
        waitForText("Undo")
        compose.onNodeWithText("Undo").performClick()
        waitUntilEditEnabled(source, false)
        assertFalse(checkNotNull(edits.read(source)).cuts.single().enabled)
        compose.onNodeWithText("Apply").performClick()
        waitUntilEditEnabled(source, true)
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Save edited copy").performClick()
        compose.waitUntil(EDIT_EXPORT_TIMEOUT) { !jobs.busy && jobs.exportedFile?.isFile == true }
        val exported = checkNotNull(jobs.exportedFile)
        ownedSources += exported
        assertTrue(exported.isFile)
        assertEquals(originalHash, hash(source))
        assertEquals(persisted, edits.read(source))
        assertPlayableWithAudioAndVideo(exported)
        val expectedEditedDuration = persisted.keptRanges().sumOf { it.endMs - it.startMs }
        assertTrue(
            "Export must follow the active non-destructive decision",
            kotlin.math.abs(videoDurationMs(exported) - expectedEditedDuration) <= 500L,
        )
        ownedSources += exported
    }

    private fun awaitNewStartedSession(before: Set<String>): String {
        var result: String? = null
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            val candidate = runCatching {
                captureStore.sessions().firstOrNull { id ->
                    id !in before && captureStore.snapshot(id).captureStarted
                }
            }.getOrNull()
            if (candidate != null) {
                result = candidate
                ownedSessions += candidate
            }
            candidate != null
        }
        return checkNotNull(result) { "Camera did not create a durable capture session" }
    }

    private fun waitForPauseJob(source: File) {
        compose.waitUntil(EDIT_EXPORT_TIMEOUT) {
            !jobs.busy && runCatching { runBlocking { jobs.readEdits(source) != null } }.getOrDefault(false)
        }
    }

    private fun awaitState(session: String, predicate: (EngineState) -> Boolean): EngineState {
        var result: EngineState? = null
        compose.waitUntil(FILE_WAIT_TIMEOUT) {
            val candidate = runCatching { captureStore.snapshot(session) }.getOrNull()
            if (candidate != null && predicate(candidate)) result = candidate
            result != null
        }
        runBlocking { coordinator.flush() }
        return checkNotNull(result) { "Capture did not reach expected state: $session" }
    }

    private fun waitUntilEditEnabled(source: File, enabled: Boolean) {
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            edits.read(source)?.cuts?.singleOrNull()?.enabled == enabled
        }
    }

    private fun waitForPlayerToFinish(player: MediaPlayer) {
        val deadline = SystemClock.uptimeMillis() + PLAYER_WAIT_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline && player.isPlaying) {
            SystemClock.sleep(100L)
        }
        SystemClock.sleep(400L)
    }

    private fun copyAssetToCache(prefix: String, asset: String = PAUSE_FIXTURE): File {
        val file = File(context.cacheDir, "$prefix-${SystemClock.elapsedRealtime()}.mp4")
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun assertPlayableWithAudioAndVideo(file: File) {
        assertTrue("Missing media: ${file.name}", file.isFile && file.length() > 0L)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            assertTrue(videoDurationMs(retriever) > 0L)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            assertTrue("Video track must be playable", width > 0 && height > 0)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally {
            retriever.release()
        }
    }

    private fun videoDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            videoDurationMs(retriever)
        } finally {
            retriever.release()
        }
    }

    private fun videoDurationMs(retriever: MediaMetadataRetriever): Long =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    private fun cleanupSource(source: File) {
        runCatching { captions.remove(source) }
        runCatching { edits.remove(source) }
        runCatching { com.example.one_take.vision.FaceTrackRepository(context).remove(source) }
        runCatching { projects.remove(source) }
        runCatching { runBlocking { coordinator.remove(source) } }
        runCatching { videoStore.deleteVideo(source) }
        if (source.parentFile == context.cacheDir) source.delete()
    }

    private fun captureLedger(session: String): File = File(
        File(context.noBackupFilesDir, "capture_ledgers"), "$session.ledger",
    )

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun grantRuntimePermission(permission: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission",
            ),
        ).use { it.readBytes() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForContentDescription(description: String) {
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForEnabledContentDescription(description: String) {
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            runCatching { compose.onNodeWithContentDescription(description).assertIsEnabled() }.isSuccess
        }
    }

    private companion object {
        const val PAUSE_FIXTURE = "room-pause-test.mp4"
        const val START_RECORDING = "Start recording"
        const val STOP_RECORDING = "Stop recording"
        const val SAVED_VIDEOS = "Saved videos"
        const val REVIEW_VIDEO = "Review video"
        const val SAMPLE_RATE = 16_000
        const val UI_WAIT_TIMEOUT = 30_000L
        const val FILE_WAIT_TIMEOUT = 60_000L
        const val EDIT_EXPORT_TIMEOUT = 120_000L
        const val PLAYER_WAIT_TIMEOUT = 20_000L
    }
}
