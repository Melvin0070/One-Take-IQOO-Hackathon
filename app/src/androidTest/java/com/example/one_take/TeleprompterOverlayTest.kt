package com.example.one_take

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.onetake.engine.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TeleprompterOverlayTest {
    @get:Rule val compose = createComposeRule()

    private class FakeMatcher(private val session: RecordingSession) : ScriptMatcher {
        override var progress by mutableStateOf(ScriptProgress(listOf(
            ChunkCoverage(ScriptChunk("first", "Today we introduce the camera and explain how recording works.")),
            ChunkCoverage(ScriptChunk("second", "Next we explore the settings and choose our preferred quality.")),
            ChunkCoverage(ScriptChunk("third", "Finally we save the finished video into the local library.")),
        ), 0, ScriptProgressReason.INITIAL))
        override fun consume(segment: ScriptTranscript) {
            progress = progress.copy(currentIndex = 1, reason = ScriptProgressReason.TRANSCRIPT,
                chunks = progress.chunks.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(state = ScriptChunkState.COVERED, coverage = 1.0, attempts = 1) else entry
                })
        }
        override fun next(sample: Long) {
            progress = progress.copy(currentIndex = progress.currentIndex + 1, reason = ScriptProgressReason.MANUAL_NEXT,
                chunks = progress.chunks.mapIndexed { index, entry ->
                    if (index == progress.currentIndex) entry.copy(state = ScriptChunkState.SKIPPED) else entry
                })
            session.record(sample, Change.ScriptProgressObserved(progress), ClockDomain.CAPTURE_ESTIMATE)
        }
        override fun previous(sample: Long) {
            progress = progress.copy(currentIndex = progress.currentIndex - 1, reason = ScriptProgressReason.MANUAL_PREVIOUS)
            session.record(sample, Change.ScriptProgressObserved(progress), ClockDomain.CAPTURE_ESTIMATE)
        }
    }

    @Test fun injectedStateMovesHighlightAndNextEmitsManualSkip() {
        val events = mutableListOf<Change>()
        val fake = FakeMatcher(RecordingSession { _, change, _ -> events += change })
        compose.setContent { TeleprompterOverlay(fake.progress, { fake.next(16000) }, { fake.previous(16000) }) }
        compose.onNodeWithTag("script-chunk-0").assertIsSelected()
        compose.onNodeWithTag("script-chunk-1").assertIsNotSelected()
        compose.runOnIdle { fake.consume(ScriptTranscript("advance", "fake signal", 16000)) }
        compose.onNodeWithTag("script-chunk-1").assertIsSelected()
        compose.onNodeWithTag("script-chunk-0").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Covered"))
        compose.onNodeWithText("Chunk 2 of 3").assertIsDisplayed()
        compose.onNodeWithTag("script-chunk-2").performClick()
        compose.onNodeWithTag("script-chunk-2").assertIsSelected()
        compose.onNodeWithTag("script-chunk-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Skipped"))
        compose.runOnIdle {
            val skip = events.single() as Change.ScriptProgressObserved
            assertEquals(ScriptProgressReason.MANUAL_NEXT, skip.progress.reason)
            assertEquals(ScriptChunkState.SKIPPED, skip.progress.chunks[1].state)
        }
        compose.onNodeWithTag("script-previous").performClick()
        compose.onNodeWithTag("script-chunk-1").assertIsSelected()
        compose.runOnIdle {
            assertEquals(ScriptProgressReason.MANUAL_PREVIOUS,
                (events.last() as Change.ScriptProgressObserved).progress.reason)
        }
    }
}
