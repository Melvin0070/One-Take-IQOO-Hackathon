package com.example.one_take

import android.Manifest
import android.media.MediaMetadataRetriever
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.editing.EditRepository
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.LiveCaptureStore
import com.example.one_take.features.CaptionFeatureStore
import com.example.one_take.vision.FaceTrackRepository
import com.onetake.engine.Change
import com.onetake.engine.EngineState
import com.onetake.engine.SessionPhase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-stage process-death probe.
 *
 * Run [prepareProcessDeathProbe] with processProbe=prepare, force-stop the
 * target package from the host, then run [verifyProcessDeathProbe] with
 * processProbe=verify. Ordinary instrumentation runs skip both stages.
 */
@RunWith(AndroidJUnit4::class)
class LiveEngineProcessDeathTest {
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
    private val featureStore by lazy { CaptionFeatureStore.get(context) }
    private val markerFile by lazy { File(context.cacheDir, PROBE_MARKER_NAME) }

    private var mode: String = ""
    private var restoreCaptionSetting = false
    private var modelWasInstalled = false
    private val ownedSessions = linkedSetOf<String>()
    private var timedOutWithoutKill = false

    @Before
    fun setUp() {
        mode = InstrumentationRegistry.getArguments().getString(PROBE_ARGUMENT).orEmpty()
        grantRuntimePermission(Manifest.permission.CAMERA)
        grantRuntimePermission(Manifest.permission.RECORD_AUDIO)

        if (mode !in PROBE_MODES) return

        val marker = readMarker()
        compose.waitUntil(timeoutMillis = UI_WAIT_TIMEOUT) {
            featureStore.state !is com.example.one_take.features.CaptionFeatureState.Checking
        }
        restoreCaptionSetting = marker?.optBoolean(CAPTION_ENABLED_KEY, false)
            ?: featureStore.enabled
        modelWasInstalled = marker?.optBoolean(MODEL_INSTALLED_KEY, featureStore.installed)
            ?: featureStore.installed
        if (modelWasInstalled) {
            compose.runOnIdle { featureStore.setFeatureEnabled(false) }
        }

        // The verify stage starts from a fresh target process after the host
        // force-stop. Recreate also clears the permission-gate composition.
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Assisted Mode").performClick()
        waitForEnabledContentDescription(START_RECORDING)
    }

    @After
    fun tearDown() {
        if (mode !in PROBE_MODES) return

        runCatching {
            compose.activityRule.scenario.moveToState(
                androidx.lifecycle.Lifecycle.State.CREATED,
            )
        }
        runCatching { runBlocking { coordinator.flush() } }

        // A prepare test that was not killed must clean its own capture before
        // reporting the failed handoff. A killed process never reaches @After.
        if (mode == PREPARE && timedOutWithoutKill) {
            ownedSessions.forEach { session ->
                val state = runCatching { captureStore.snapshot(session) }.getOrNull() ?: return@forEach
                state.captureSourceName?.let { cleanupOwnedSource(File(videoStore.directory, it)) }
            }
            markerFile.delete()
        }

        if (mode == VERIFY) {
            readMarker()?.optString(SOURCE_NAME_KEY).orEmpty().takeIf { it.isNotBlank() }?.let {
                cleanupOwnedSource(File(videoStore.directory, it))
            }
            markerFile.delete()
        }

        if (modelWasInstalled) {
            instrumentation.runOnMainSync { featureStore.setFeatureEnabled(restoreCaptionSetting) }
        }
    }

    @Test
    fun prepareProcessDeathProbe() {
        assumeTrue("Run with -e processProbe prepare", mode == PREPARE)

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
        SystemClock.sleep(MIN_CAPTURE_MILLIS)

        val capture = captureStore.snapshot(checkNotNull(session))
        val sourceName = checkNotNull(capture.captureSourceName)
        writeMarker(
            JSONObject()
                .put(VERSION_KEY, 1)
                .put(SESSION_ID_KEY, session)
                .put(SOURCE_NAME_KEY, sourceName)
                .put(CAPTION_ENABLED_KEY, restoreCaptionSetting)
                .put(MODEL_INSTALLED_KEY, modelWasInstalled)
                .put(PREPARED_AT_KEY, SystemClock.elapsedRealtime()),
        )

        // The host must force-stop this target process. If it does not, leave a
        // clear failure instead of silently converting the probe into a normal
        // stop. A force-stop terminates the instrumentation before this loop
        // returns, so the failure path is only used for a missing host action.
        val deadline = SystemClock.uptimeMillis() + PREPARE_HANDOFF_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) SystemClock.sleep(250L)
        timedOutWithoutKill = true
        fail("processProbe=prepare was not force-stopped within 45 seconds")
    }

    @Test
    fun verifyProcessDeathProbe() {
        assumeTrue("Run with -e processProbe verify", mode == VERIFY)
        val marker = readMarker()
        assertNotNull("No process probe marker was left by the prepare stage", marker)
        val probe = checkNotNull(marker)

        val session = probe.optString(SESSION_ID_KEY)
        val sourceName = probe.optString(SOURCE_NAME_KEY)
        assertTrue("Probe marker has a session id", session.isNotBlank())
        assertTrue("Probe marker has a source name", sourceName.isNotBlank())
        assertTrue("The restarted process must still see the session journal", session in captureStore.sessions())

        runBlocking { coordinator.flush() }
        val interrupted = awaitState(session) { it.phase == SessionPhase.INTERRUPTED }
        val history = runBlocking { coordinator.history(session) }
        assertTrue(
            "The journal must record why process death interrupted capture",
            history.any {
                val change = it.change as? Change.CaptureInterrupted
                change?.reason == "process_restarted"
            },
        )
        assertEquals(SessionPhase.INTERRUPTED, interrupted.phase)

        // Going through Projects invokes VideoStore recovery and then the
        // coordinator's capture-to-project adoption path.
        compose.onNodeWithContentDescription(SAVED_VIDEOS).performClick()
        waitForText(SAVED_VIDEOS)
        val source = File(videoStore.directory, sourceName)
        val playableBeforeRecovery = isPlayableVideo(source)
        waitForRecoveryToSettle(session, source, playableBeforeRecovery)
        runBlocking { coordinator.flush() }

        val afterRecovery = captureStore.snapshot(session)
        val playableAfterRecovery = isPlayableVideo(source)
        val project = if (playableAfterRecovery) projectStore.read(source) else null
        // Keep a small machine-readable outcome beside the marker for the host
        // runner. This records whether this device had a valid finalized raw
        // file after process death, including the intentional partial-file path.
        writeResult(
            JSONObject()
                .put(VERSION_KEY, 1)
                .put(SESSION_ID_KEY, session)
                .put(SOURCE_NAME_KEY, sourceName)
                .put(PHASE_KEY, afterRecovery.phase.name)
                .put(SOURCE_EXISTS_KEY, source.isFile)
                .put(PLAYABLE_KEY, playableAfterRecovery)
                .put(PROJECT_SESSION_KEY, project?.sessionId ?: JSONObject.NULL),
        )
        if (playableAfterRecovery) {
            assertEquals(
                "A valid finalized raw recording must recover to READY",
                SessionPhase.READY,
                afterRecovery.phase,
            )
            assertNotNull("Recovered media must have an adopted project", project)
            assertEquals("Recovery must preserve the capture UUID", session, project!!.sessionId)
        } else {
            // Invalid or missing partial media is deliberately retained as an
            // interrupted journal. This test does not call it recoverable video.
            assertEquals(SessionPhase.INTERRUPTED, afterRecovery.phase)
            assertFalse("An invalid partial file must not be treated as playable", isPlayableVideo(source))
        }
    }

    private fun waitForRecoveryToSettle(session: String, source: File, playableBeforeRecovery: Boolean) {
        val marker = File(videoStore.directory, ".${source.name}.pending")
        if (playableBeforeRecovery) {
            compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
                val state = runCatching { captureStore.snapshot(session) }.getOrNull()
                state?.phase == SessionPhase.READY && !marker.exists() &&
                    compose.onAllNodesWithContentDescription("Play " + source.name).fetchSemanticsNodes().isNotEmpty()
            }
        } else {
            // Invalid or missing partial media has no READY transition. Allow
            // the library recovery coroutine to run, then retain its interrupted
            // journal regardless of whether the marker could be removed.
            SystemClock.sleep(1_000L)
            compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
                val state = runCatching { captureStore.snapshot(session) }.getOrNull()
                state?.phase == SessionPhase.INTERRUPTED && !isPlayableVideo(source)
            }
        }
    }

    private fun awaitState(session: String, predicate: (EngineState) -> Boolean): EngineState {
        var result: EngineState? = null
        compose.waitUntil(timeoutMillis = FILE_WAIT_TIMEOUT) {
            val candidate = runCatching { captureStore.snapshot(session) }.getOrNull()
            if (candidate != null && predicate(candidate)) result = candidate
            result != null
        }
        return checkNotNull(result)
    }

    private fun cleanupOwnedSource(source: File) {
        runCatching { captionRepository.remove(source) }
        runCatching { editRepository.remove(source) }
        runCatching { faceRepository.remove(source) }
        runCatching { projectStore.remove(source) }
        runCatching { runBlocking { coordinator.remove(source) } }
        runCatching { videoStore.deleteVideo(source) }
    }

    private fun readMarker(): JSONObject? = runCatching {
        if (!markerFile.isFile) return null
        JSONObject(markerFile.readText())
    }.getOrNull()

    private fun writeMarker(marker: JSONObject) {
        val temporary = File(context.cacheDir, "$PROBE_MARKER_NAME.tmp")
        temporary.writeText(marker.toString())
        check(temporary.renameTo(markerFile)) { "Unable to publish process probe marker" }
    }

    private fun writeResult(result: JSONObject) {
        val resultFile = File(context.cacheDir, PROBE_RESULT_NAME)
        val temporary = File(context.cacheDir, "$PROBE_RESULT_NAME.tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(resultFile)) { "Unable to publish process probe result" }
    }

    private fun isPlayableVideo(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            duration > 0L && width > 0 && height > 0
        } catch (_: Exception) {
            false
        } finally {
            retriever.release()
        }
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
        const val PROBE_ARGUMENT = "processProbe"
        const val PREPARE = "prepare"
        const val VERIFY = "verify"
        val PROBE_MODES = setOf(PREPARE, VERIFY)
        const val PROBE_MARKER_NAME = "engine-process-probe.json"
        const val PROBE_RESULT_NAME = "engine-process-result.json"
        const val VERSION_KEY = "version"
        const val SESSION_ID_KEY = "sessionId"
        const val SOURCE_NAME_KEY = "sourceName"
        const val CAPTION_ENABLED_KEY = "captionEnabled"
        const val MODEL_INSTALLED_KEY = "modelInstalled"
        const val PREPARED_AT_KEY = "preparedAtElapsedMs"
        const val PHASE_KEY = "phase"
        const val SOURCE_EXISTS_KEY = "sourceExists"
        const val PLAYABLE_KEY = "playable"
        const val PROJECT_SESSION_KEY = "projectSessionId"
        const val START_RECORDING = "Start recording"
        const val STOP_RECORDING = "Stop recording"
        const val SAVED_VIDEOS = "Projects"
        const val MIN_CAPTURE_MILLIS = 1_500L
        const val UI_WAIT_TIMEOUT = 25_000L
        const val FILE_WAIT_TIMEOUT = 45_000L
        const val PREPARE_HANDOFF_TIMEOUT_MS = 45_000L
    }
}
