package com.example.one_take.director

import com.example.one_take.vision.FaceObservation
import kotlin.math.abs

/** A conservative pre-take setup suggestion. */
internal data class CaptureGuidancePrompt(
    val kind: String,
    val message: String,
    val atMs: Long,
)

/**
 * Turns stable face observations into one setup chip at a time.
 *
 * Conditions must remain stable before a prompt is emitted. A condition must
 * clear before it can prompt again, so a persistent framing problem cannot nag.
 */
internal class CaptureGuidance {
    private var lastElapsedMs = Long.MIN_VALUE
    private var pendingKind: String? = null
    private var pendingSinceMs = 0L
    private var pendingLastMs = Long.MIN_VALUE
    private var activeKind: String? = null
    private val promptedKinds = HashSet<String>()
    private val lastPromptAtMs = HashMap<String, Long>()

    fun observe(observation: FaceObservation?, elapsedMs: Long): CaptureGuidancePrompt? {
        val now = advanceTime(elapsedMs)
        val condition = conditionFor(observation)
        if (condition == null) {
            clearCondition()
            return null
        }
        if (activeKind != condition.kind) {
            activeKind?.let(promptedKinds::remove)
            activeKind = condition.kind
        }
        if (pendingKind != condition.kind || pendingLastMs == now ||
            now - pendingLastMs > MAX_SAMPLE_GAP_MS
        ) {
            pendingKind = condition.kind
            pendingSinceMs = now
            pendingLastMs = now
            return null
        }
        pendingLastMs = now
        val lastPrompt = lastPromptAtMs[condition.kind]
        if (condition.kind in promptedKinds ||
            (lastPrompt != null && now - lastPrompt < PROMPT_COOLDOWN_MS)
        ) {
            return null
        }
        if (now - pendingSinceMs < STABILITY_MS) return null
        promptedKinds += condition.kind
        lastPromptAtMs[condition.kind] = now
        return CaptureGuidancePrompt(condition.kind, condition.message, now)
    }

    fun reset() {
        lastElapsedMs = Long.MIN_VALUE
        pendingKind = null
        pendingSinceMs = 0L
        pendingLastMs = Long.MIN_VALUE
        activeKind = null
        promptedKinds.clear()
        lastPromptAtMs.clear()
    }

    private fun conditionFor(observation: FaceObservation?): Condition? {
        if (observation == null) return null
        return when {
            observation.width >= MAX_FACE_WIDTH -> Condition(KIND_CLOSE, CLOSE_MESSAGE)
            abs(observation.centerX - CENTER_X) >= CENTER_TOLERANCE ->
                Condition(KIND_CENTER, CENTER_MESSAGE)
            observation.backgroundLuminance - observation.faceLuminance >= BACKLIGHT_DELTA ->
                Condition(KIND_BACKLIGHT, BACKLIGHT_MESSAGE)
            else -> null
        }
    }

    private fun clearCondition() {
        activeKind?.let(promptedKinds::remove)
        activeKind = null
        pendingKind = null
        pendingLastMs = Long.MIN_VALUE
    }

    private fun advanceTime(elapsedMs: Long): Long {
        val candidate = elapsedMs.coerceAtLeast(0L)
        if (lastElapsedMs == Long.MIN_VALUE || candidate > lastElapsedMs) lastElapsedMs = candidate
        return lastElapsedMs
    }

    private data class Condition(val kind: String, val message: String)

    private companion object {
        const val KIND_CLOSE = "close"
        const val KIND_CENTER = "center"
        const val KIND_BACKLIGHT = "backlight"
        const val CLOSE_MESSAGE = "Step back, you're too close"
        const val CENTER_MESSAGE = "Center yourself"
        const val BACKLIGHT_MESSAGE = "You're backlit, turn 90°"
        const val STABILITY_MS = 500L
        const val MAX_SAMPLE_GAP_MS = 1_000L
        const val PROMPT_COOLDOWN_MS = 8_000L
        const val MAX_FACE_WIDTH = 0.45f
        const val CENTER_X = 0.5f
        const val CENTER_TOLERANCE = 0.18f
        const val BACKLIGHT_DELTA = 0.20f
    }
}
