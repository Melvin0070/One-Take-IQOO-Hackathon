package com.example.one_take

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecorderControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledRecordingControlCannotStartAnotherRecording() {
        var starts = 0
        compose.setContent { RecordButton(recording = false, enabled = false, onClick = { starts++ }) }
        compose.onNodeWithContentDescription("Start recording").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun recordingControlAnnouncesStopAndAcceptsStopAction() {
        val recording = mutableStateOf(true)
        compose.setContent { RecordButton(recording = recording.value, enabled = true, onClick = { recording.value = false }) }
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test fun deniedPermissionsOfferSettingsAndSavedVideos() {
        var settingsOpened = false
        var libraryOpened = false
        compose.setContent {
            PermissionScreen(true, onRequest = { error("Must use Settings for permanent denial") },
                onOpenSettings = { settingsOpened = true }, onOpenLibrary = { libraryOpened = true })
        }
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Saved videos").performClick()
        compose.runOnIdle {
            assertEquals(true, settingsOpened)
            assertEquals(true, libraryOpened)
        }
    }
}
