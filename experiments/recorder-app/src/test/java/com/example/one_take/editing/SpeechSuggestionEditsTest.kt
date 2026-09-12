package com.example.one_take.editing

import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionWord
import org.junit.Assert.*
import org.junit.Test

class SpeechSuggestionEditsTest {
    private val captions = listOf(CaptionSegment(0, 2_000, "um hello", listOf(
        CaptionWord(200, 500, "um", .9f), CaptionWord(600, 1_500, "hello", .95f),
    )))

    @Test fun suggestionsStartDisabledAndRepeatedAnalysisPreservesApplyAndUndo() {
        val result = withSpeechSuggestions(EditDecision(3_000, emptyList()), captions)
        val cut = result.cuts.single()
        assertFalse(cut.enabled)
        assertEquals(listOf(SourceRange(0, 3_000)), result.keptRanges())
        val applied = result.toggle(cut.id)
        assertEquals(applied, withSpeechSuggestions(applied, captions))
        assertEquals(result, withSpeechSuggestions(applied.restoreAll(), captions))
    }

    @Test fun existingPauseConflictIsSkippedRatherThanWidened() {
        val old = EditDecision(3_000, listOf(EditCut("pause", 100, 300, "silence")))
        assertEquals(old, withSpeechSuggestions(old, captions))
    }

    @Test fun correctedTranscriptRemovesPendingButKeepsExplicitlyAppliedCut() {
        val pending = withSpeechSuggestions(EditDecision(3_000, emptyList()), captions)
        assertTrue(withSpeechSuggestions(pending, emptyList()).cuts.isEmpty())
        val applied = pending.toggle(pending.cuts.single().id)
        assertEquals(applied, withSpeechSuggestions(applied, emptyList()))
    }

    @Test fun pauseReanalysisPreservesAppliedSpeechCutsAndUndonePauses() {
        val old = EditDecision(3_000, listOf(
            EditCut("speech-test", 200, 500, "Possible filler", true),
            EditCut("pause", 1_000, 1_500, "silence", false),
        ))
        val fresh = EditDecision(3_000, listOf(
            EditCut("new-overlap", 100, 700, "silence"),
            EditCut("pause", 1_000, 1_500, "silence"),
            EditCut("new", 2_000, 2_500, "silence"),
        ))
        val merged = mergeDetectedPauses(old, fresh)
        assertEquals(old.cuts, merged.cuts.take(2))
        assertEquals("new", merged.cuts.last().id)
    }

    @Test fun pendingSuggestionLabelRefreshesWhenRecognizedWordChangesAtSameTime() {
        val initial = withSpeechSuggestions(EditDecision(3_000, emptyList()), captions)
        val corrected = captions.map { it.copy(text = "uh hello", words = it.words.map { word ->
            if (word.text == "um") word.copy(text = "uh", confidence = .8f) else word
        }) }
        val refreshed = withSpeechSuggestions(initial, corrected)
        assertTrue(refreshed.cuts.single().reason.contains("uh"))
        assertTrue(refreshed.cuts.single().reason.contains("80%"))
        assertFalse(refreshed.cuts.single().enabled)
    }

    @Test fun missingWordEvidenceDoesNotInventCutTimes() {
        assertTrue(withSpeechSuggestions(EditDecision(3_000, emptyList()),
            listOf(CaptionSegment(0, 2_000, "um hello"))).cuts.isEmpty())
    }
}
