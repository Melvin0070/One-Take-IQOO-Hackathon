package com.example.one_take

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.one_take.captions.CaptionSegment
import com.onetake.engine.*

/** Owns matching outside Compose and forwards committed caption snapshots once per segment. */
internal class ScriptCaptureController(
    script: String,
    session: RecordingSession,
) {
    private val matcher: ScriptMatcher = FuzzyScriptMatcher(script, session)
    var progress by mutableStateOf(matcher.progress)
        private set
    fun consume(segments: List<CaptionSegment>) {
        segments.forEach { segment -> matcher.consume(ScriptTranscript(
            "${segment.startMs}:${segment.endMs}", segment.text,
            Timeline().samplesFromMillis(segment.endMs.coerceAtLeast(0)),
            Timeline().samplesFromMillis(segment.startMs.coerceIn(0, segment.endMs.coerceAtLeast(0))))) }
        progress = matcher.progress
    }
    fun next(elapsedMs: Long) { matcher.next(Timeline().samplesFromMillis(elapsedMs.coerceAtLeast(0))); progress = matcher.progress }
    fun previous(elapsedMs: Long) { matcher.previous(Timeline().samplesFromMillis(elapsedMs.coerceAtLeast(0))); progress = matcher.progress }
}
