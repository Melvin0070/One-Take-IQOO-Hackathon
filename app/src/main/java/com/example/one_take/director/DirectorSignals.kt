package com.example.one_take.director

import com.example.one_take.captions.CaptionSegment
import java.util.Locale

/** A reversible suggestion emitted by the capture-time Director. */
internal data class DirectorPrompt(
    val kind: String,
    val message: String,
    val atMs: Long,
)

/**
 * Conservative, stateful signals for the first Director prompts.
 *
 * This class only suggests a prompt. It never edits or deletes source media.
 * [elapsedMs] is treated as a session clock and is made monotonic when callers
 * deliver a revised or out-of-order observation.
 */
internal class DirectorSignals {
    private var lastElapsedMs = Long.MIN_VALUE
    private val lastPromptAtMs = HashMap<String, Long>()
    private val seenMarkers = ArrayList<MarkerOccurrence>()

    private var quietAudioSinceMs: Long? = null
    private var lastQuietAudioAtMs: Long? = null
    private var quietAudioPrompted = false
    private var offAxisSinceMs: Long? = null
    private var lastOffAxisAtMs: Long? = null
    private var offAxisPrompted = false

    /** Emits one retake suggestion for each newly observed explicit self-repair marker. */
    fun observeTranscript(segments: List<CaptionSegment>, elapsedMs: Long): DirectorPrompt? {
        val now = advanceTime(elapsedMs)
        val occurrences = segments.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .flatMap { segment -> findMarkers(segment).asSequence() }
            .sortedBy { it.atMs }
            .toList()

        for (occurrence in occurrences) {
            if (seenMarkers.any { it.isSameOccurrenceAs(occurrence) }) continue
            seenMarkers += occurrence
            if (canEmit(KIND_SELF_REPAIR, now)) {
                return emit(KIND_SELF_REPAIR, REPEAT_MESSAGE, now)
            }
        }
        return null
    }
    /** Emits a suggestion only after two low-confidence, quiet observations span one second. */
    fun observeAudio(confidence: Float?, rms: Float, elapsedMs: Long): DirectorPrompt? {
        val now = advanceTime(elapsedMs)
        val quietAndUncertain = confidence != null &&
            confidence.isFinite() &&
            confidence <= LOW_CONFIDENCE_THRESHOLD &&
            rms.isFinite() &&
            rms >= 0f &&
            rms <= QUIET_RMS_THRESHOLD

        if (!quietAndUncertain) {
            quietAudioSinceMs = null
            lastQuietAudioAtMs = null
            quietAudioPrompted = false
            return null
        }

        if (quietAudioSinceMs == null) {
            quietAudioSinceMs = now
            lastQuietAudioAtMs = now
            return null
        }
        if (lastQuietAudioAtMs == now) return null
        lastQuietAudioAtMs = now
        if (!quietAudioPrompted && now - (quietAudioSinceMs ?: now) >= AUDIO_PERSISTENCE_MS) {
            val prompt = if (canEmit(KIND_MUMBLE, now)) {
                emit(KIND_MUMBLE, SPEAK_MESSAGE, now)
            } else {
                null
            }
            if (prompt != null) quietAudioPrompted = true
            return prompt
        }
        return null
    }
    /** Emits one lens-direction suggestion after an uninterrupted one-second gaze drop. */
    fun observeGaze(offAxis: Boolean, elapsedMs: Long): DirectorPrompt? {
        val now = advanceTime(elapsedMs)
        if (!offAxis) {
            offAxisSinceMs = null
            lastOffAxisAtMs = null
            offAxisPrompted = false
            return null
        }

        if (offAxisSinceMs == null) {
            offAxisSinceMs = now
            lastOffAxisAtMs = now
            return null
        }
        if (lastOffAxisAtMs == now) return null
        lastOffAxisAtMs = now
        if (!offAxisPrompted && now - (offAxisSinceMs ?: now) > GAZE_PERSISTENCE_MS) {
            val prompt = if (canEmit(KIND_GAZE, now)) {
                emit(KIND_GAZE, LOOK_MESSAGE, now)
            } else {
                null
            }
            if (prompt != null) offAxisPrompted = true
            return prompt
        }
        return null
    }
    /** Clears all observations and starts a new monotonic session. */
    fun reset() {
        lastElapsedMs = Long.MIN_VALUE
        lastPromptAtMs.clear()
        seenMarkers.clear()
        quietAudioSinceMs = null
        lastQuietAudioAtMs = null
        quietAudioPrompted = false
        offAxisSinceMs = null
        lastOffAxisAtMs = null
        offAxisPrompted = false
    }
    private fun advanceTime(elapsedMs: Long): Long {
        val candidate = elapsedMs.coerceAtLeast(0L)
        if (lastElapsedMs == Long.MIN_VALUE || candidate > lastElapsedMs) {
            lastElapsedMs = candidate
        }
        return lastElapsedMs
    }

    private fun canEmit(kind: String, now: Long): Boolean {
        val previous = lastPromptAtMs[kind] ?: return true
        return now - previous >= PROMPT_COOLDOWN_MS
    }

    private fun emit(kind: String, message: String, now: Long): DirectorPrompt {
        lastPromptAtMs[kind] = now
        return DirectorPrompt(kind, message, now)
    }

    private fun findMarkers(segment: CaptionSegment): List<MarkerOccurrence> {
        val text = segment.text
        val lowerText = text.lowercase(Locale.ROOT)
        val occurrences = ArrayList<MarkerOccurrence>()
        for (marker in REPAIR_MARKERS) {
            val lowerMarker = marker.lowercase(Locale.ROOT)
            var searchFrom = 0
            while (searchFrom < lowerText.length) {
                val index = lowerText.indexOf(lowerMarker, searchFrom)
                if (index < 0) break
                val end = index + lowerMarker.length
                if (isBoundary(lowerText, index - 1) && isBoundary(lowerText, end)) {
                    val span = segment.endMs - segment.startMs
                    val offset = (span.toDouble() * index / text.length.coerceAtLeast(1)).toLong()
                    occurrences += MarkerOccurrence(
                        marker = lowerMarker,
                        atMs = segment.startMs + offset.coerceIn(0L, span),
                    )
                }
                searchFrom = (index + lowerMarker.length).coerceAtLeast(index + 1)
            }
        }
        return occurrences
    }

    private fun isBoundary(text: String, index: Int): Boolean =
        index !in text.indices || !text[index].isLetterOrDigit()

    private data class MarkerOccurrence(
        val marker: String,
        val atMs: Long,
    ) {
        fun isSameOccurrenceAs(other: MarkerOccurrence): Boolean =
            marker == other.marker && absoluteDifference(atMs, other.atMs) <= MARKER_REVISED_TOLERANCE_MS
    }

    private companion object {
        const val KIND_SELF_REPAIR = "self-repair"
        const val KIND_MUMBLE = "mumble"
        const val KIND_GAZE = "gaze"
        const val REPEAT_MESSAGE = "Repeat that line"
        const val SPEAK_MESSAGE = "Speak clearly"
        const val LOOK_MESSAGE = "Look toward the lens"

        const val PROMPT_COOLDOWN_MS = 8_000L
        const val AUDIO_PERSISTENCE_MS = 1_000L
        const val GAZE_PERSISTENCE_MS = 1_000L
        const val MARKER_REVISED_TOLERANCE_MS = 1_500L
        const val LOW_CONFIDENCE_THRESHOLD = 0.45f
        const val QUIET_RMS_THRESHOLD = 0.012f
        val REPAIR_MARKERS = listOf("sorry", "let me repeat", "i mean", "मेरा मतलब", "फिर से")

        fun absoluteDifference(first: Long, second: Long): Long =
            if (first >= second) first - second else second - first
    }
}
