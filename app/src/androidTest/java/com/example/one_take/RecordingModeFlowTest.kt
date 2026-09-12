package com.example.one_take

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RecordingModeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("recording_setup", Context.MODE_PRIVATE)
    private var oldDraft: String? = null
    private var oldScript: String? = null
    private var oldClipboard: ClipData? = null
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun prepare() {
        oldDraft = preferences.getString("draft", null)
        oldScript = preferences.getString("script", null)
        preferences.edit().clear().commit()
        compose.runOnIdle { oldClipboard = clipboard.primaryClip }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${context.packageName} $permission"
            )).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
    }

    @After fun restore() {
        preferences.edit().clear().putString("draft", oldDraft).putString("script", oldScript).commit()
        compose.runOnIdle { clipboard.setPrimaryClip(oldClipboard ?: ClipData.newPlainText("", "")) }
    }

    @Test fun assistedCameraAndBackHome() {
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
        compose.onNodeWithText("Assisted Mode").performClick()
        waitForCamera()
        compose.onNodeWithTag("teleprompter-overlay").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test fun pasteContinueAndCameraRecreationRetainScript() {
        compose.onNodeWithText("Script Mode").performClick()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("script", "Welcome to One Take")) }
        compose.onNodeWithText("Paste from Clipboard").performClick()
        compose.onNodeWithText("4 words").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        waitForCamera()
        compose.onNodeWithTag("teleprompter-overlay").assertIsDisplayed()
        compose.onNodeWithText("Welcome to One Take").assertIsDisplayed()
        assertEquals("", RecordingSetupStore(context).draft)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Welcome to One Take").assertIsDisplayed()
        compose.onNodeWithTag("live-transcript").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("Assisted Mode").performClick()
        compose.onNodeWithTag("teleprompter-overlay").assertDoesNotExist()
    }

    @Test fun draftSurvivesRecreationAndLeavingEntry() {
        compose.onNodeWithText("Script Mode").performClick()
        compose.onNodeWithTag("script-input").performTextInput("A durable draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("script-input").assertTextContains("A durable draft")
        // Close the keyboard, then use the explicit navigation button.
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Script Mode").performClick()
        compose.onNodeWithTag("script-input").assertTextContains("A durable draft")
        assertEquals("A durable draft", RecordingSetupStore(context).draft)
        compose.onNodeWithTag("script-input").performTextReplacement("   \n  ")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test fun projectsReturnHome() {
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("Projects").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    private fun waitForCamera() {
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithContentDescription("Start recording").assertIsEnabled() }.isSuccess
        }
    }
}
