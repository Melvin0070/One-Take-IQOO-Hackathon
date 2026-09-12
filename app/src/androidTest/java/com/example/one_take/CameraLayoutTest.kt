package com.example.one_take

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CameraLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var originalGrid = true
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("camera_settings", 0)

    @After fun restoreSettings() {
        preferences.edit().putBoolean("grid", originalGrid).commit()
    }

    @Before fun allowCamera() {
        originalGrid = preferences.getBoolean("grid", true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${instrumentation.targetContext.packageName} $permission"
            )).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Assisted Mode").performClick()
    }

    @Test fun cameraSettingsAreAvailableFromPreview() {
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithText("Camera settings").assertIsDisplayed()
        compose.onNodeWithText("Composition grid").assertIsDisplayed()
        compose.onNodeWithText("Recording quality").assertIsDisplayed()
        compose.onNodeWithText("Composition grid").performClick()
        compose.onNodeWithText("Done").performClick()
        if (originalGrid) compose.onNodeWithTag("composition-grid").assertDoesNotExist()
        else compose.onNodeWithTag("composition-grid").assertExists()
        compose.activityRule.scenario.recreate()
        if (originalGrid) compose.onNodeWithTag("composition-grid").assertDoesNotExist()
        else compose.onNodeWithTag("composition-grid").assertExists()
        compose.onNodeWithContentDescription("Projects").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recording preset").assertDoesNotExist()
        compose.onNodeWithText("VIDEO").assertDoesNotExist()
    }
}
