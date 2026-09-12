package com.example.one_take

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.ui.theme.OneTakeTheme
import com.onetake.engine.android.vision.VisualSuggestion
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VisualSuggestionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store by lazy { VideoStore(context.filesDir) }
    private var existingFiles = emptySet<String>()

    @Before fun grantPermissions() {
        existingFiles = store.directory.listFiles().orEmpty().map { it.name }.toSet()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission",
            )).use { it.readBytes() }
        }
    }

    @After fun removeOnlyTestRecording() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(60_000) {
            store.directory.listFiles().orEmpty().none {
                it.name !in existingFiles && it.name.endsWith(".pending")
            } && !CaptionJobs.get(context).busy
        }
        store.directory.listFiles().orEmpty()
            .filter { it.name !in existingFiles && it.extension == "mp4" }
            .forEach { source ->
                runBlocking { CaptionJobs.get(context).removeMetadata(source) }
                assertTrue("Unable to remove test video ${source.name}", store.deleteVideo(source))
            }
    }

    @Test fun tapShowsInjectedSuggestionsWhileRealRecordingTimerContinues() {
        val calls = AtomicInteger()
        val suggestion = VisualSuggestion(
            VisualSuggestion.Kind.MOVE_CLOSER,
            "Move closer for this test",
            VisualSuggestion.Severity.INFO,
        )
        compose.setContent {
            CompositionLocalProvider(LocalVisualAnalyzer provides { frame, _ ->
                assertNotSame("Analysis must run off the main thread", Looper.getMainLooper(), Looper.myLooper())
                assertTrue("Must capture an actual preview bitmap", frame.width > 0 && frame.height > 0)
                if (calls.incrementAndGet() == 1) listOf(suggestion) else emptyList()
            }) {
                OneTakeTheme { VideoRecorderApp() }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Start recording").fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        assertEquals("No analysis before a tap", 0, calls.get())
        compose.onNodeWithContentDescription("Start recording").performClick()
        compose.waitUntil(20_000) { timerText()?.contains("00:00") == false }
        compose.onNodeWithText("Visual Suggestions").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(suggestion.message).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(suggestion.message).assertIsDisplayed()
        val timerWhenSheetOpened = requireNotNull(timerText())
        compose.waitUntil(10_000) {
            timerText()?.let { it != timerWhenSheetOpened } == true
        }
        val timerWhileSheetOpen = requireNotNull(timerText())
        compose.onNodeWithText(suggestion.message).assertIsDisplayed()
        assertEquals("Analysis must not repeat on a timer", 1, calls.get())
        println("Recording timer continued with sheet open: $timerWhenSheetOpened -> $timerWhileSheetOpen")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(context.cacheDir, "visual-suggestions-test.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Visual Suggestions").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Looks good").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Looks good").assertIsDisplayed()
        assertEquals(2, calls.get())
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithContentDescription("Stop recording").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            store.directory.listFiles().orEmpty().any {
                it.name !in existingFiles && it.extension == "mp4" && it.length() > 0
            } && store.directory.listFiles().orEmpty().none {
                it.name !in existingFiles && it.name.endsWith(".pending")
            }
        }
    }

    private fun timerText(): String? {
        val matcher = SemanticsMatcher("recording timer") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("●") } == true
        }
        return compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    }
}
