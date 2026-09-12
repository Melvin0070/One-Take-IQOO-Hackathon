package com.example.one_take

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.ui.theme.OneTakeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transcriptUpdatesAndRetainsOnlyThreeLines() {
        val segments = mutableStateOf(listOf(CaptionSegment(0, 100, "First")))
        compose.setContent {
            OneTakeTheme {
                RecordingOverlay(RecordingMode.Assisted, "", segments.value, true, true, true, {})
            }
        }
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.runOnIdle {
            segments.value = listOf("First", "Second", "Third", "Fourth").mapIndexed { i, text ->
                CaptionSegment(i * 100L, (i + 1) * 100L, text)
            }
        }
        compose.onNodeWithText("First").assertDoesNotExist()
        compose.onNodeWithTag("transcript-lines").assertTextEquals("Second\nThird\nFourth")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("transcript-lines").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(3, layouts.single().lineCount)
        compose.runOnIdle { segments.value = listOf(CaptionSegment(0, 100, "Long speech ".repeat(100) + "Latest words")) }
        compose.onNodeWithTag("transcript-lines").assertTextContains("Latest words", substring = true)
        layouts.clear()
        compose.onNodeWithTag("transcript-lines").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(3, layouts.single().lineCount)
        compose.runOnIdle { segments.value = emptyList() }
        compose.onNodeWithTag("transcript-lines").assertDoesNotExist()
    }

    @Test fun scriptOwnsOverlaySlot() {
        compose.setContent {
            RecordingOverlay(RecordingMode.Script, "Read this script", listOf(CaptionSegment(0, 100, "Spoken")),
                true, true, true, {})
        }
        compose.onNodeWithTag("teleprompter-overlay").assertIsDisplayed()
        compose.onNodeWithText("Read this script").assertIsDisplayed()
        compose.onNodeWithTag("live-transcript").assertDoesNotExist()
        compose.onNodeWithText("Spoken").assertDoesNotExist()
    }

    @Test fun missingModelOffersMarketplaceWithoutBlockingRecording() {
        var opens = 0
        val recording = mutableStateOf(false)
        compose.setContent {
            RecordingOverlay(RecordingMode.Assisted, "", emptyList(), recording.value, false, false, { opens++ })
        }
        compose.onNodeWithText("Add live transcript").performClick()
        assertEquals(1, opens)
        compose.runOnIdle { recording.value = true }
        compose.onNodeWithText("You can record without a transcript.").assertIsDisplayed()
        compose.onNodeWithText("Add live transcript").assertDoesNotExist()
    }
}
