package com.example.one_take.editing

import com.example.one_take.captions.CaptionSegment
import com.example.one_take.engine.recordingTimeline
import com.example.one_take.engine.toEngine
import com.onetake.engine.SpeechSuggestion
import com.onetake.engine.SpeechSuggestionDetector
import kotlin.math.roundToInt

private const val SPEECH_ID_PREFIX = "speech-"

/** Adds review-only speech suggestions without widening or re-enabling existing cuts. */
internal fun withSpeechSuggestions(decision: EditDecision, captions: List<CaptionSegment>): EditDecision {
    val suggestions = SpeechSuggestionDetector.detect(captions.map { it.toEngine() }).mapNotNull { suggestion ->
        val start = recordingTimeline.msFromSamples(suggestion.startSample)
        val end = recordingTimeline.msFromSamples(suggestion.endSample)
        if (start < 0 || end <= start || end > decision.durationMs) return@mapNotNull null
        val kind = when (suggestion.kind) {
            SpeechSuggestion.Kind.FILLER -> "Possible filler"
            SpeechSuggestion.Kind.REPEATED_WORD -> "Possible repeated word"
            SpeechSuggestion.Kind.RESTART -> "Possible restart"
        }
        EditCut(
            id = SPEECH_ID_PREFIX + suggestion.id,
            startMs = start,
            endMs = end,
            reason = "$kind: “${suggestion.excerpt}” · recognition confidence ${(suggestion.confidence * 100).roundToInt()}%",
            enabled = false,
        )
    }
    val byId = suggestions.associateBy { it.id }
    val retained = decision.cuts.mapNotNull { cut ->
        if (!cut.id.startsWith(SPEECH_ID_PREFIX) || cut.enabled) cut else byId[cut.id]
    }.toMutableList()
    suggestions.forEach { suggested ->
        if (retained.none { it.id == suggested.id || overlaps(it, suggested) }) retained += suggested
    }
    return EditDecision(decision.durationMs, retained.sortedBy { it.startMs })
}

/** A new analysis can add pauses, but cannot discard an explicit Apply or Undo decision. */
internal fun mergeDetectedPauses(previous: EditDecision?, detected: EditDecision): EditDecision {
    if (previous == null) return detected
    require(previous.durationMs == detected.durationMs) { "Analysis duration does not match the recording" }
    val retained = previous.cuts.toMutableList()
    detected.cuts.forEach { cut ->
        if (retained.none { it.id == cut.id || overlaps(it, cut) }) retained += cut
    }
    return EditDecision(detected.durationMs, retained.sortedBy { it.startMs })
}

private fun overlaps(first: EditCut, second: EditCut): Boolean =
    first.startMs < second.endMs && second.startMs < first.endMs
