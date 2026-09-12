package com.example.one_take

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionJobs
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

class ScriptRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tenLineScriptManualSkipIsJournaledDuringRealRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val features = CaptionFeatureStore.get(context)
        val store = LiveCaptureStore(File(context.noBackupFilesDir, "capture_ledgers"))
        val coordinator = LiveCaptureCoordinator.get(context)
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission")).use { it.readBytes() }
        }
        compose.waitUntil(15000) { features.state !is CaptionFeatureState.Checking }
        val wasEnabled = features.enabled
        val installed = features.installed
        val setup = context.getSharedPreferences("recording_setup", Context.MODE_PRIVATE)
        val oldDraft = setup.getString("draft", null)
        val oldScript = setup.getString("script", null)
        val before = store.sessions().toSet()
        var ownedSession: String? = null
        try {
            if (installed) compose.runOnIdle { features.setFeatureEnabled(false) }
            setup.edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Script Mode").performClick()
            compose.onNodeWithTag("script-input").performTextInput((1..10).joinToString("\n") {
                "Line $it introduces a different part of our recording demonstration."
            })
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Chunk 1 of 10").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chunk 1 of 10").assertIsDisplayed()
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("Start recording").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Start recording").assertIsEnabled().performClick()
            compose.waitUntil(15000) {
                store.sessions().firstOrNull { it !in before && store.snapshot(it).captureStarted }
                    ?.also { ownedSession = it } != null
            }
            compose.onNodeWithTag("script-chunk-1").performClick()
            compose.onNodeWithText("Chunk 2 of 10").assertIsDisplayed()
            compose.waitUntil(15000) {
                store.snapshot(checkNotNull(ownedSession)).scriptProgress?.reason == ScriptProgressReason.MANUAL_NEXT
            }
            val skipped = store.snapshot(checkNotNull(ownedSession)).scriptProgress!!
            assertEquals(ScriptChunkState.SKIPPED, skipped.chunks.first().state)
            Thread.sleep(2000)
            compose.onNodeWithContentDescription("Stop recording").performClick()
            compose.waitUntil(30000) { store.snapshot(checkNotNull(ownedSession)).phase == SessionPhase.READY }
            val final = store.snapshot(checkNotNull(ownedSession))
            assertEquals(skipped, final.scriptProgress)
            val original = File(VideoStore(context.filesDir).directory, checkNotNull(final.captureSourceName))
            assertTrue("Original video must remain intact", original.isFile && original.length() > 0)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val jobs = CaptionJobs.get(context)
            instrumentation.runOnMainSync { jobs.cancel() }
            compose.waitUntil(30000) { !jobs.busy }
            runBlocking { coordinator.flush() }
            ownedSession?.let { id ->
                compose.waitUntil(30000) { store.snapshot(id).phase !in setOf(SessionPhase.RECORDING, SessionPhase.FINALIZING) }
                val source = File(VideoStore(context.filesDir).directory, checkNotNull(store.snapshot(id).captureSourceName))
                runBlocking { jobs.removeMetadata(source); coordinator.remove(source) }
                VideoStore(context.filesDir).deleteVideo(source)
            }
            if (installed) instrumentation.runOnMainSync { features.setFeatureEnabled(wasEnabled) }
            setup.edit().clear().putString("draft", oldDraft).putString("script", oldScript).commit()
        }
    }
}
