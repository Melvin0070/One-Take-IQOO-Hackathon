package com.example.one_take.captions

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Finds a bounded timestamp offset between recorded and live 16 kHz mono PCM. */
internal object AudioAlignment {
    private const val SAMPLE_RATE = 16_000
    private const val ENVELOPE_MS = 10
    private const val ENVELOPE_SAMPLES = SAMPLE_RATE * ENVELOPE_MS / 1_000
    private const val MIN_SIGNAL_RMS = 0.0001f
    private const val MIN_OVERLAP_FRAMES = 30
    private const val MIN_MATCHED_SPEECH_FRAMES = 50
    private const val MIN_CORRELATION = 0.80

    /**
     * Returns the offset satisfying `referenceTime = liveTime + offsetMs`.
     *
     * The search compares 10 ms RMS envelopes and is bounded by [maxOffsetMs].  A null
     * result means that there is not enough audible material or that the two signals do
     * not have a sufficiently strong, consistently aligned envelope.
     */
    fun offsetMs(
        reference: FloatArray,
        live: FloatArray,
        maxOffsetMs: Int = 2_500,
    ): Long? {
        if (maxOffsetMs < 0) {
            return null
        }
        val referenceEnvelope = rmsEnvelope(reference)
        val liveEnvelope = rmsEnvelope(live)
        if (referenceEnvelope.isEmpty() || liveEnvelope.isEmpty()) {
            return null
        }

        val referencePeak = referenceEnvelope.maxOrNull() ?: return null
        val livePeak = liveEnvelope.maxOrNull() ?: return null
        if (referencePeak < MIN_SIGNAL_RMS || livePeak < MIN_SIGNAL_RMS) {
            return null
        }

        val referenceThreshold = max(MIN_SIGNAL_RMS, referencePeak * SPEECH_FRACTION)
        val liveThreshold = max(MIN_SIGNAL_RMS, livePeak * SPEECH_FRACTION)
        val referenceSpeech = speechBounds(referenceEnvelope, referenceThreshold) ?: return null
        val liveSpeech = speechBounds(liveEnvelope, liveThreshold) ?: return null
        if (referenceSpeech.count < MIN_MATCHED_SPEECH_FRAMES || liveSpeech.count < MIN_MATCHED_SPEECH_FRAMES) {
            return null
        }

        val expectedFirstDelta = referenceSpeech.first - liveSpeech.first
        val expectedLastDelta = referenceSpeech.last - liveSpeech.last
        // Offsets larger than the longer envelope cannot leave the minimum usable
        // overlap, so cap the loop even if a caller supplies a very large bound.
        val maxOffsetFrames = minOf(
            maxOffsetMs / ENVELOPE_MS,
            maxOf(referenceEnvelope.size, liveEnvelope.size) - MIN_OVERLAP_FRAMES,
        )
        if (maxOffsetFrames < 0) {
            return null
        }
        // A boundary is useful evidence only when speech is not clipped by that
        // array's edge.  Continuous speech can begin or end at an edge, in which
        // case its first/last active frame carries no offset information.
        val firstBoundaryReliable = referenceSpeech.first > 0 && liveSpeech.first > 0
        val lastBoundaryReliable =
            referenceSpeech.last < referenceEnvelope.lastIndex && liveSpeech.last < liveEnvelope.lastIndex

        var best: Candidate? = null
        for (offsetFrames in -maxOffsetFrames..maxOffsetFrames) {
            val candidate = evaluate(
                referenceEnvelope = referenceEnvelope,
                liveEnvelope = liveEnvelope,
                referenceThreshold = referenceThreshold,
                liveThreshold = liveThreshold,
                offsetFrames = offsetFrames,
                expectedFirstDelta = expectedFirstDelta,
                expectedLastDelta = expectedLastDelta,
                firstBoundaryReliable = firstBoundaryReliable,
                lastBoundaryReliable = lastBoundaryReliable,
            ) ?: continue
            if (best == null || candidate.isBetterThan(best!!)) {
                best = candidate
            }
        }

        val result = best ?: return null
        if (result.correlation < MIN_CORRELATION) {
            return null
        }
        val referenceStart = maxOf(0, result.offsetFrames)
        val liveStart = maxOf(0, -result.offsetFrames)
        val half = result.overlap / 2
        // Reject an offset that matches one portion but drifts in the other.
        for (start in listOf(0, half)) {
            val count = if (start == 0) half else result.overlap - half
            val score = correlation(referenceEnvelope, liveEnvelope,
                referenceStart + start, liveStart + start, count)
            if (score != null && score < 0.65) return null
        }
        return result.offsetFrames.toLong() * ENVELOPE_MS
    }

    private fun evaluate(
        referenceEnvelope: FloatArray,
        liveEnvelope: FloatArray,
        referenceThreshold: Float,
        liveThreshold: Float,
        offsetFrames: Int,
        expectedFirstDelta: Int,
        expectedLastDelta: Int,
        firstBoundaryReliable: Boolean,
        lastBoundaryReliable: Boolean,
    ): Candidate? {
        val referenceStart = maxOf(0, offsetFrames)
        val liveStart = maxOf(0, -offsetFrames)
        val overlap = minOf(
            referenceEnvelope.size - referenceStart,
            liveEnvelope.size - liveStart,
        )
        if (overlap < MIN_OVERLAP_FRAMES) {
            return null
        }

        val firstError = abs(offsetFrames - expectedFirstDelta)
        val lastError = abs(offsetFrames - expectedLastDelta)
        // Startup transients and gain changes make threshold crossings unreliable.
        // They are tie-breakers only; the entire overlapping signal decides the match.

        var matchedSpeech = 0
        var speechUnion = 0
        for (index in 0 until overlap) {
            val referenceActive = referenceEnvelope[referenceStart + index] >= referenceThreshold
            val liveActive = liveEnvelope[liveStart + index] >= liveThreshold
            if (referenceActive || liveActive) {
                speechUnion++
            }
            if (referenceActive && liveActive) {
                matchedSpeech++
            }
        }
        if (matchedSpeech < MIN_MATCHED_SPEECH_FRAMES) {
            return null
        }
        // An unrelated active signal should not be able to win by correlating only a
        // short speech fragment against a long silent overlap.
        if (matchedSpeech * 2 < speechUnion) {
            return null
        }

        // Shared silence must not make unrelated short bursts appear correlated.
        val speechScore = correlation(referenceEnvelope, liveEnvelope,
            referenceStart, liveStart, overlap, referenceThreshold, liveThreshold) ?: return null
        if (speechScore < 0.65) return null
        val correlation = correlation(
            referenceEnvelope,
            liveEnvelope,
            referenceStart,
            liveStart,
            overlap,
        ) ?: return null
        return Candidate(
            offsetFrames = offsetFrames,
            correlation = correlation,
            firstError = if (firstBoundaryReliable) firstError else 0,
            lastError = if (lastBoundaryReliable) lastError else 0,
            overlap = overlap,
        )
    }

    private fun correlation(
        reference: FloatArray,
        live: FloatArray,
        referenceStart: Int,
        liveStart: Int,
        count: Int,
        referenceThreshold: Float = Float.NEGATIVE_INFINITY,
        liveThreshold: Float = Float.NEGATIVE_INFINITY,
    ): Double? {
        var selected = 0
        var referenceMean = 0.0
        var liveMean = 0.0
        for (index in 0 until count) {
            if (reference[referenceStart + index] < referenceThreshold &&
                live[liveStart + index] < liveThreshold) continue
            selected++
            referenceMean += reference[referenceStart + index].toDouble()
            liveMean += live[liveStart + index].toDouble()
        }
        if (selected < 2) return null
        referenceMean /= selected
        liveMean /= selected

        var numerator = 0.0
        var referenceEnergy = 0.0
        var liveEnergy = 0.0
        for (index in 0 until count) {
            if (reference[referenceStart + index] < referenceThreshold &&
                live[liveStart + index] < liveThreshold) continue
            val referenceDelta = reference[referenceStart + index] - referenceMean
            val liveDelta = live[liveStart + index] - liveMean
            numerator += referenceDelta * liveDelta
            referenceEnergy += referenceDelta * referenceDelta
            liveEnergy += liveDelta * liveDelta
        }
        val denominator = sqrt(referenceEnergy * liveEnergy)
        if (denominator <= 1.0e-9) {
            return null
        }
        return numerator / denominator
    }

    private fun rmsEnvelope(samples: FloatArray): FloatArray {
        val frameCount = samples.size / ENVELOPE_SAMPLES
        if (frameCount == 0) {
            return FloatArray(0)
        }
        return FloatArray(frameCount) { frame ->
            val start = frame * ENVELOPE_SAMPLES
            var sumSquares = 0.0
            for (sampleIndex in start until start + ENVELOPE_SAMPLES) {
                val sample = samples[sampleIndex].toDouble()
                if (!sample.isFinite()) {
                    continue
                }
                sumSquares += sample * sample
            }
            sqrt(sumSquares / ENVELOPE_SAMPLES).toFloat()
        }
    }

    private fun speechBounds(envelope: FloatArray, threshold: Float): SpeechBounds? {
        var first = -1
        var last = -1
        var count = 0
        for (index in envelope.indices) {
            if (envelope[index] >= threshold) {
                if (first < 0) {
                    first = index
                }
                last = index
                count++
            }
        }
        return if (first >= 0 && last >= first) SpeechBounds(first, last, count) else null
    }

    private data class SpeechBounds(
        val first: Int,
        val last: Int,
        val count: Int,
    )

    private data class Candidate(
        val offsetFrames: Int,
        val correlation: Double,
        val firstError: Int,
        val lastError: Int,
        val overlap: Int,
    ) {
        fun isBetterThan(other: Candidate): Boolean {
            if (correlation != other.correlation) {
                return correlation > other.correlation
            }
            val boundaryError = firstError + lastError
            val otherBoundaryError = other.firstError + other.lastError
            if (boundaryError != otherBoundaryError) {
                return boundaryError < otherBoundaryError
            }
            return overlap > other.overlap
        }
    }

    private const val SPEECH_FRACTION = 0.10f
}
