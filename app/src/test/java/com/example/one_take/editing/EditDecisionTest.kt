package com.example.one_take.editing

import com.example.one_take.captions.CaptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertThrows

class EditDecisionTest {
    @Test
    fun keptRangesAndSourceMappingRespectCutEdges() {
        val decision = EditDecision(
            durationMs = 10_000L,
            cuts = listOf(
                EditCut("pause-1", 2_000L, 3_000L, "silence"),
                EditCut("pause-2", 5_000L, 6_000L, "silence"),
            ),
        )

        assertEquals(
            listOf(
                SourceRange(0L, 2_000L),
                SourceRange(3_000L, 5_000L),
                SourceRange(6_000L, 10_000L),
            ),
            decision.keptRanges(),
        )
        assertEquals(1_999L, decision.sourceToEditedTime(1_999L))
        assertNull(decision.sourceToEditedTime(2_000L))
        assertEquals(2_000L, decision.sourceToEditedTime(3_000L))
        assertNull(decision.sourceToEditedTime(5_000L))
        assertEquals(4_000L, decision.sourceToEditedTime(6_000L))
        assertEquals(8_000L, decision.sourceToEditedTime(10_000L))
        assertNull(decision.sourceToEditedTime(-1L))
        assertNull(decision.sourceToEditedTime(10_001L))
    }

    @Test
    fun toggleAndRestorePreserveTheOriginalCutDecisions() {
        val decision = EditDecision(
            durationMs = 4_000L,
            cuts = listOf(
                EditCut("first", 500L, 1_500L, "silence"),
                EditCut("second", 2_000L, 3_000L, "manual", enabled = false),
            ),
        )

        val enabled = decision.toggle("second")
        assertEquals(listOf(SourceRange(0L, 500L), SourceRange(1_500L, 2_000L), SourceRange(3_000L, 4_000L)), enabled.keptRanges())
        assertEquals(false, enabled.toggle("first").cuts.first { it.id == "first" }.enabled)
        assertEquals(decision, decision.toggle("unknown"))

        assertEquals(
            listOf(false, false),
            enabled.restoreAll().cuts.map { it.enabled },
        )
        assertEquals(listOf(SourceRange(0L, 4_000L)), enabled.restoreAll().keptRanges())
    }

    @Test
    fun captionsCrossingAcutAreSplitAndRetimed() {
        val decision = EditDecision(
            durationMs = 8_000L,
            cuts = listOf(EditCut("pause", 2_000L, 4_000L, "silence")),
        )

        val captions = decision.mapCaptions(
            listOf(
                CaptionSegment(500L, 1_000L, "before"),
                CaptionSegment(1_000L, 5_000L, "crosses pause"),
                CaptionSegment(5_000L, 6_000L, "after"),
            ),
        )

        assertEquals(
            listOf(
                CaptionSegment(500L, 1_000L, "before"),
                CaptionSegment(1_000L, 2_000L, "crosses pause"),
                CaptionSegment(2_000L, 3_000L, "crosses pause"),
                CaptionSegment(3_000L, 4_000L, "after"),
            ),
            captions,
        )
    }

    @Test
    fun aDecisionCannotDeleteTheWholeRecording() {
        assertThrows(IllegalArgumentException::class.java) {
            EditDecision(
                durationMs = 1_000L,
                cuts = listOf(EditCut("everything", 0L, 1_000L, "silence")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditDecision(
                durationMs = 1_000L,
                cuts = listOf(EditCut("too-much", 0L, 800L, "silence")),
            )
        }
    }
}
