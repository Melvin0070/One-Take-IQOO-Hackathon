package com.example.one_take

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.editing.EditRepository
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.LiveCaptureStore
import com.example.one_take.features.CaptionFeatureState
import com.example.one_take.vision.FaceObservation
import com.example.one_take.vision.FaceTrackRepository
import com.onetake.engine.Change
import com.onetake.engine.ClockDomain
import com.onetake.engine.EngineState
import com.onetake.engine.SessionPhase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device coverage for the app-to-engine capture lifecycle. */
@RunWith(AndroidJUnit4::class)
class LiveEngineLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context by lazy { instrumentation.targetContext }
    private val videoStore by lazy { VideoStore(context.filesDir) }
    private val captureStore by lazy {
        LiveCaptureStore(File(context.noBackupFilesDir, "capture_ledgers"))
    }
    private val coordinator by lazy { LiveCaptureCoordinator.get(context) }
    private val projectStore by lazy { EngineProjectStore(context) }
    private val captionRepository by lazy { CaptionRepository(context) }
    private val editRepository by lazy { EditRepository(context) }
    private val faceRepository by lazy { FaceTrackRepository(context) }
    private val featureStore by lazy { com.example.one_take.features.CaptionFeatureStore.get(context) }

    private var originalCaptionEnabled = false
    private var hadInstalledCaptionModel = false
    private val ownedSessions = linkedSetOf<String>()

    @Before
    fun setUp() {
        grantRuntimePermission(Manifest.permission.CAMERA)
        grantRuntimePermission(Manifest.permission.RECORD_AUDIO)

        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            featureStore.state !is CaptionFeatureState.Checking
        }
        hadInstalledCaptionModel = featureStore.installed
        originalCaptionEnabled = featureStore.enabled
        if (hadInstalledCaptionModel) {
            compose.runOnIdle { featureStore.setFeatureEnabled(false) }
        }

        // Permission grants can race the first composition of the rule.
        compose.activityRule.scenario.recreate()
        waitForEnabledContentDescription(START_RECORDING)
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        }
        // Captions-disabled captures now also own a pause-analysis job.
        val jobs = com.example.one_take.captions.CaptionJobs.get(context)
        runCatching { instrumentation.runOnMainSync { jobs.cancel() } }
        runCatching { compose.waitUntil(UI_WAIT_TIMEOUT) { !jobs.busy } }
        runCatching { runBlocking { coordinator.flush() } }

        // A session is the ownership boundary.  Resolve its source name from the
        // journal before removing anything, so pre-existing user recordings stay
        // untouched even when the test process has other files in the directory.
        ownedSessions.forEach { session ->
            awaitTerminal(session)
            val state = runCatching { captureStore.snapshot(session) }.getOrNull() ?: return@forEach
            val sourceName = state.captureSourceName ?: return@forEach
            cleanupOwnedSource(File(videoStore.directory, sourceName))
        }

        if (hadInstalledCaptionModel) {
            instrumentation.runOnMainSync { featureStore.setFeatureEnabled(originalCaptionEnabled) }
        }
    }

    @Test
    fun normalCapturePersistsProvisionalAndVisionEventsWithoutFinalCaptions() {
        val session = startCaptureAndAwaitStarted()
        val observationAt = SystemClock.elapsedRealtime()

        coordinator.transcript(
            session,
            listOf(CaptionSegment(startMs = 250L, endMs = 900L, text = "provisional line")),
        )
        val injectedObservation = FaceObservation(
            timestampMs = observationAt,
            centerX = 0.5f,
            centerY = 0.42f,
            width = 0.32f,
            height = 0.48f,
            faceLuminance = 0.62f,
            backgroundLuminance = 0.30f,
            offAxis = false,
        )
        // Vision is deliberately coalesced with the camera stream. Re-submit
        // the test observation at the boundary so at least one accepted sample
        // is present even when a real camera sample arrives at the same time.
        repeat(3) {
            coordinator.vision(session, injectedObservation)
            runBlocking { coordinator.flush() }
        }

        val duringHistory = runBlocking { coordinator.history(session) }
        val provisional = duringHistory.singleOrNull { it.change is Change.ProvisionalTranscript }
        val vision = duringHistory.firstOrNull { it.change is Change.VisionObserved }
        assertNotNull("Provisional transcript must be journaled", provisional)
        assertEquals(ClockDomain.RECOGNIZER, provisional!!.clock)
        assertNotNull("Vision observations must be journaled", vision)
        assertEquals(ClockDomain.CAPTURE_ESTIMATE, vision!!.clock)
        assertTrue("Capture observations must have a non-negative sample", vision.sample >= 0L)
        assertNull("A provisional transcript is not a final caption list", captureStore.snapshot(session).captions)

        stopThroughUi()
        val state = awaitState(session) { it.phase == SessionPhase.READY }
        val source = sourceFor(state)
        assertPlayableWithAudioAndVideo(source)

        val finalHistory = runBlocking { coordinator.history(session) }
        assertTrue(finalHistory.any { it.change is Change.SourceFinalized })
        assertFalse(
            "The capture engine must not invent final captions from provisional input",
            finalHistory.any { it.change is Change.CaptionsReplaced },
        )
        assertNull(state.captions)
    }

    @Test
    fun backgroundingDuringCapturePersistsBackgroundStopAndFinalizesRawMedia() {
        val session = startCaptureAndAwaitStarted(minCaptureMillis = 0L)
        SystemClock.sleep(MIN_CAPTURE_MILLIS)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val state = awaitState(session) { it.phase == SessionPhase.READY }
        val source = sourceFor(state)
        assertPlayableWithAudioAndVideo(source)

        val history = runBlocking { coordinator.history(session) }
        val stop = history.map { it.change }
            .filterIsInstance<Change.StopRequested>()
            .lastOrNull()
        assertEquals("background", stop?.reason)
        assertTrue(history.any { it.change is Change.SourceFinalized })
        assertTrue(
            "Background stop must happen before media finalization",
            history.indexOfFirst { it.change is Change.StopRequested } <
                history.indexOfFirst { it.change is Change.SourceFinalized },
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForText(REVIEW_VIDEO)
    }

    @Test
    fun activityRecreationDuringCaptureRetainsRawMediaAndAdoptsSameProjectSession() {
        val session = startCaptureAndAwaitStarted()
        val sourceName = checkNotNull(captureStore.snapshot(session).captureSourceName)
        SystemClock.sleep(MIN_CAPTURE_MILLIS)

        compose.activityRule.scenario.recreate()
        val state = awaitState(session) { it.phase == SessionPhase.READY }
        val source = sourceFor(state)
        assertEquals(sourceName, source.name)
        assertPlayableWithAudioAndVideo(source)

        // Opening Saved videos exercises the same recovery path a user sees after
        // Activity recreation, rather than reading the project ledger alone.
        waitForEnabledContentDescription(START_RECORDING)
        compose.onNodeWithContentDescription(SAVED_VIDEOS).performClick()
        waitForText(SAVED_VIDEOS)
        waitForText(source.name)

        val adopted = projectStore.read(source)
        assertNotNull("Finalized capture must be adopted into the project ledger", adopted)
        assertEquals("Capture and project ledgers must share the same UUID", session, adopted!!.sessionId)
        assertEquals(SessionPhase.READY, adopted.phase)
        assertEquals(session, captureStore.snapshot(session).sessionId)
        assertTrue(source.isFile)
    }

    @Test
    fun rapidStopAfterCameraStartFinalizesWithoutLeavingPendingCapture() {
        val session = startCaptureAndAwaitStarted(minCaptureMillis = 0L)

        // The Stop control is intentionally clicked as soon as CameraX reports
        // Start. This covers the short user capture path without exercising ASR.
        compose.onNodeWithContentDescription(STOP_RECORDING).performClick()
        val state = awaitState(session) {
            it.phase == SessionPhase.READY || it.phase == SessionPhase.FAILED
        }
        val source = sourceFor(state)
        if (state.phase == SessionPhase.READY) {
            assertPlayableWithAudioAndVideo(source)
            assertTrue(runBlocking { coordinator.history(session) }.any { it.change is Change.SourceFinalized })
        } else {
            // A stop before the encoder emits its first frame can legitimately
            // produce CameraX ERROR_NO_VALID_DATA. It is still a clean terminal
            // lifecycle outcome as long as the journal retains the reason.
            assertEquals(SessionPhase.FAILED, state.phase)
            assertTrue(state.failureReason.orEmpty().startsWith("finalize_error_"))
            assertFalse(source.exists())
            assertTrue(runBlocking { coordinator.history(session) }.any { it.change is Change.CaptureFailed })
        }
        assertFalse(
            "A completed rapid stop must not leave its pending marker locked",
            videoStore.directory.listFiles().orEmpty().any {
                it.name == ".${source.name}.pending"
            },
        )
    }

    @Test
    fun coordinatorRejectsWrongSourceAndRetainsInvalidMediaForRetry() {
        val token = SystemClock.elapsedRealtime()
        val original = File(context.cacheDir, "engine-finalize-original-$token.mp4")
        val wrong = File(context.cacheDir, "engine-finalize-wrong-$token.mp4")
        instrumentation.context.assets.open("caption-test.mp4").use { input ->
            original.outputStream().use { input.copyTo(it) }
        }
        instrumentation.context.assets.open("caption-test.mp4").use { input ->
            wrong.outputStream().use { input.copyTo(it) }
        }
        val invalid = File(context.cacheDir, "engine-finalize-invalid-$token.mp4")
        invalid.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        var wrongSession: String? = null
        var invalidSession: String? = null
        try {
            wrongSession = runBlocking { coordinator.begin(original) }
            ownedSessions += checkNotNull(wrongSession)
            coordinator.started(checkNotNull(wrongSession))
            runBlocking { coordinator.flush() }
            assertThrows(Exception::class.java) {
                runBlocking { coordinator.finalized(checkNotNull(wrongSession), wrong) }
            }
            assertEquals(SessionPhase.RECORDING, captureStore.snapshot(checkNotNull(wrongSession)).phase)
            coordinator.cancelled(checkNotNull(wrongSession))
            runBlocking { coordinator.flush() }

            val invalidBytes = invalid.readBytes()
            invalidSession = runBlocking { coordinator.begin(invalid) }
            ownedSessions += checkNotNull(invalidSession)
            coordinator.started(checkNotNull(invalidSession))
            runBlocking { coordinator.flush() }
            assertThrows(Exception::class.java) {
                runBlocking { coordinator.finalized(checkNotNull(invalidSession), invalid) }
            }
            runBlocking { coordinator.flush() }
            val invalidState = captureStore.snapshot(checkNotNull(invalidSession))
            assertEquals(SessionPhase.INTERRUPTED, invalidState.phase)
            assertTrue(
                runBlocking { coordinator.history(checkNotNull(invalidSession)) }.any {
                    val change = it.change as? Change.CaptureInterrupted
                    change?.reason == "finalization_failed"
                },
            )
            assertArrayEquals("Invalid media must remain available for recovery inspection", invalidBytes, invalid.readBytes())

            // Retry the same interrupted session after the partial file has
            // been replaced by valid media. Recovery must keep its UUID rather
            // than creating a second project ledger for the same source path.
            instrumentation.context.assets.open("caption-test.mp4").use { input ->
                invalid.outputStream().use { input.copyTo(it) }
            }
            runBlocking { coordinator.recover(listOf(invalid)) }
            runBlocking { coordinator.flush() }
            val recovered = captureStore.snapshot(invalidSession)
            assertEquals(SessionPhase.READY, recovered.phase)
            assertEquals(invalidSession, projectStore.read(invalid)?.sessionId)
        } finally {
            // These direct coordinator fixtures live in cacheDir, so clean
            // their exact source names explicitly before the normal session
            // teardown maps VideoStore recordings.
            wrongSession?.let { runCatching { coordinator.cancelled(it) } }
            invalidSession?.let { runCatching { coordinator.cancelled(it) } }
            runCatching { runBlocking { coordinator.flush() } }
            cleanupOwnedSource(original)
            cleanupOwnedSource(wrong)
            cleanupOwnedSource(invalid)
            original.delete()
            wrong.delete()
            invalid.delete()
        }
    }

    @Test
    fun finalizationExceptionLeavesJournalUntouchedAndRecoveryKeepsSessionUuid() {
        val source = copyEngineFixture("engine-finalize-journal")
        val session = runBlocking { coordinator.begin(source) }
        ownedSessions += session
        coordinator.started(session)
        runBlocking { coordinator.flush() }
        val ledger = captureLedger(session)
        val journalBytes = ledger.readBytes()
        val rawHash = recordingFingerprint(source)
        ledger.appendText("malformed completed journal frame\n")
        val corruptJournalBytes = ledger.readBytes()
        try {
            assertThrows(Exception::class.java) {
                runBlocking { coordinator.finalized(session, source) }
            }
            // A malformed completed record is a storage failure, not an
            // instruction to rewrite or discard the valid capture history.
            assertArrayEquals(corruptJournalBytes, ledger.readBytes())
            ledger.writeBytes(journalBytes)
            assertArrayEquals(journalBytes, ledger.readBytes())

            runBlocking { coordinator.recover(listOf(source)) }
            runBlocking { coordinator.flush() }
            val recovered = captureStore.snapshot(session)
            assertEquals(SessionPhase.READY, recovered.phase)
            assertEquals(session, projectStore.read(source)?.sessionId)
            assertEquals("Recovery must not rewrite the raw media", rawHash, recordingFingerprint(source))
        } finally {
            cleanupCoordinatorFixture(source, session)
        }
    }

    @Test
    fun interruptedCallWithCorruptJournalIsRetryableAfterJournalRestore() {
        val source = copyEngineFixture("engine-interrupt-journal")
        val session = runBlocking { coordinator.begin(source) }
        ownedSessions += session
        coordinator.started(session)
        runBlocking { coordinator.flush() }
        val ledger = captureLedger(session)
        val journalBytes = ledger.readBytes()
        val rawHash = recordingFingerprint(source)
        ledger.appendText("corrupt completed journal frame\n")

        // interrupted() must report its guarded storage failure through the
        // coordinator while still removing the in-memory camera owner. Once
        // the exact journal bytes are restored, recover() can retry safely.
        coordinator.interrupted(session, "test_interrupt")
        runBlocking { coordinator.flush() }
        ledger.writeBytes(journalBytes)
        assertArrayEquals(journalBytes, ledger.readBytes())

        try {
            runBlocking { coordinator.recover(listOf(source)) }
            runBlocking { coordinator.flush() }
            val recovered = captureStore.snapshot(session)
            assertEquals(SessionPhase.READY, recovered.phase)
            assertEquals(session, projectStore.read(source)?.sessionId)
            assertEquals("Recovery must not rewrite the raw media", rawHash, recordingFingerprint(source))
        } finally {
            cleanupCoordinatorFixture(source, session)
        }
    }

    private fun startCaptureAndAwaitStarted(minCaptureMillis: Long = MIN_CAPTURE_MILLIS): String {
        val before = captureStore.sessions().toSet()
        compose.onNodeWithContentDescription(START_RECORDING).performClick()

        var session: String? = null
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            val candidate = runCatching {
                captureStore.sessions().firstOrNull { id ->
                    id !in before && captureStore.snapshot(id).captureStarted
                }
            }.getOrNull()
            if (candidate != null) {
                session = candidate
                ownedSessions += candidate
            }
            candidate != null
        }
        waitForContentDescription(STOP_RECORDING)
        if (minCaptureMillis > 0L) SystemClock.sleep(minCaptureMillis)
        return checkNotNull(session) { "Camera did not produce an engine session" }
    }

    private fun awaitTerminal(session: String) {
        val deadline = SystemClock.uptimeMillis() + FILE_WAIT_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val phase = runCatching { captureStore.snapshot(session).phase }.getOrNull()
            if (phase != SessionPhase.RECORDING && phase != SessionPhase.FINALIZING) return
            SystemClock.sleep(100L)
        }
    }

    private fun stopThroughUi() {
        compose.onNodeWithContentDescription(STOP_RECORDING).performClick()
        waitForText(REVIEW_VIDEO)
    }

    private fun awaitState(session: String, predicate: (EngineState) -> Boolean): EngineState {
        var result: EngineState? = null
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
            val candidate = runCatching { captureStore.snapshot(session) }.getOrNull()
            if (candidate != null && predicate(candidate)) result = candidate
            result != null
        }
        runBlocking { coordinator.flush() }
        return checkNotNull(result) { "Engine session did not reach the expected state: $session" }
    }

    private fun sourceFor(state: EngineState): File = File(
        videoStore.directory,
        checkNotNull(state.captureSourceName) { "Capture source name was not recorded" },
    )

    private fun copyEngineFixture(prefix: String): File {
        val source = File(context.cacheDir, "$prefix-${SystemClock.elapsedRealtime()}.mp4")
        instrumentation.context.assets.open("caption-test.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        return source
    }

    private fun captureLedger(session: String): File = File(
        File(context.noBackupFilesDir, "capture_ledgers"),
        "$session.ledger",
    )

    private fun cleanupCoordinatorFixture(source: File, session: String) {
        runCatching { runBlocking { coordinator.remove(source) } }
        runCatching { projectStore.remove(source) }
        source.delete()
        // This is the exact ledger path allocated by begin(). It is only a
        // fallback for a test that failed while the journal was unreadable.
        captureLedger(session).delete()
    }

    private fun assertPlayableWithAudioAndVideo(file: File) {
        assertTrue("Finalized raw recording is missing: ${file.name}", file.isFile && file.length() > 0L)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            assertTrue("Raw recording has no playable duration", duration > 0L)
            assertTrue("Raw recording has no video dimensions", width > 0 && height > 0)
            assertEquals("Raw recording must retain its audio track", "yes",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally {
            retriever.release()
        }
    }

    private fun cleanupOwnedSource(source: File) {
        runCatching { captionRepository.remove(source) }
        runCatching { editRepository.remove(source) }
        runCatching { faceRepository.remove(source) }
        runCatching { projectStore.remove(source) }
        runCatching { runBlocking { coordinator.remove(source) } }
        runCatching { videoStore.deleteVideo(source) }
    }

    private fun grantRuntimePermission(permission: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission",
            ),
        ).use { it.readBytes() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForContentDescription(description: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForEnabledContentDescription(description: String) {
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            runCatching { compose.onNodeWithContentDescription(description).assertIsEnabled() }.isSuccess
        }
    }

    private companion object {
        const val START_RECORDING = "Start recording"
        const val STOP_RECORDING = "Stop recording"
        const val SAVED_VIDEOS = "Saved videos"
        const val REVIEW_VIDEO = "Review video"
        const val MIN_CAPTURE_MILLIS = 1_500L
        const val UI_WAIT_TIMEOUT = 25_000L
        const val FILE_WAIT_TIMEOUT = 45_000L
    }
}
