package com.example.one_take.editing

import kotlin.math.sqrt

/** Conservative RMS-based detector for long interior pauses in normalized PCM audio. */
internal object SilenceDetector {
    private const val FRAME_MS = 20L
    private const val MIN_SILENCE_MS = 1_200L
    private const val SPEECH_PADDING_MS = 200L
    private const val MIN_STRONG_SPEECH_RMS = 0.01
    private const val MIN_THRESHOLD_RMS = 0.0001
    // Only near-silence is safe for automatic cuts. A louder room may yield no
    // cuts; that is preferable to removing a softly spoken line.
    private const val MAX_THRESHOLD_RMS = 0.0005
    private const val NOISE_MULTIPLIER = 2.5

    fun detect(samples: FloatArray, sampleRate: Int = 16_000): EditDecision {
        require(sampleRate > 0) { "Sample rate must be positive" }

        val durationMs = if (samples.isEmpty()) {
            0L
        } else {
            ((samples.size.toLong() * 1_000L) + sampleRate - 1L) / sampleRate
        }
        if (samples.isEmpty()) return EditDecision(durationMs, emptyList())

        val frameSize = maxOf(1, (sampleRate * FRAME_MS / 1_000L).toInt())
        val frameRms = buildFrameRms(samples, frameSize)
        val strongestRms = frameRms.maxOrNull() ?: 0.0
        if (strongestRms < MIN_STRONG_SPEECH_RMS) {
            // There is no reliable speech anchor. Do not trim noise or a quiet file.
            return EditDecision(durationMs, emptyList())
        }

        val noiseFloor = percentile(frameRms, 0.2)
        // Keep the threshold below a small fraction of the loudest observed
        // speech.  A loud opening must not cause a quiet spoken section to be
        // classified as silence later in the same take.
        val speechBound = minOf(MAX_THRESHOLD_RMS, strongestRms * 0.03)
        val threshold = minOf(
            noiseFloor * NOISE_MULTIPLIER,
            speechBound,
        ).coerceAtLeast(MIN_THRESHOLD_RMS.coerceAtMost(speechBound))
        if (strongestRms < threshold * 2.0) {
            // A small dynamic range usually means room noise rather than speech plus pause.
            return EditDecision(durationMs, emptyList())
        }

        val cuts = ArrayList<EditCut>()
        var frameIndex = 0
        while (frameIndex < frameRms.size) {
            if (frameRms[frameIndex] >= threshold) {
                frameIndex++
                continue
            }

            val runStartFrame = frameIndex
            while (frameIndex < frameRms.size && frameRms[frameIndex] < threshold) frameIndex++
            val runEndFrame = frameIndex

            // Keep leading and trailing silence. Only an interior pause has two speech anchors.
            if (runStartFrame == 0 || runEndFrame == frameRms.size) continue

            val rawStartSample = runStartFrame.toLong() * frameSize
            val rawEndSample = minOf(samples.size.toLong(), runEndFrame.toLong() * frameSize)
            val rawDurationMs = (rawEndSample - rawStartSample) * 1_000L / sampleRate
            if (rawDurationMs <= MIN_SILENCE_MS) continue

            val paddingSamples = sampleRate.toLong() * SPEECH_PADDING_MS / 1_000L
            val cutStartSample = rawStartSample + paddingSamples
            val cutEndSample = rawEndSample - paddingSamples
            if (cutEndSample <= cutStartSample) continue

            val cutStartMs = cutStartSample.toLong() * 1_000L / sampleRate
            val cutEndMs = cutEndSample.toLong() * 1_000L / sampleRate
            if (cutEndMs - cutStartMs < 1L) continue

            cuts += EditCut(
                id = "silence-$cutStartMs-$cutEndMs",
                startMs = cutStartMs,
                endMs = cutEndMs,
                reason = "silence",
            )
        }

        return EditDecision(durationMs, cuts)
    }

    private fun buildFrameRms(samples: FloatArray, frameSize: Int): DoubleArray {
        val frameCount = ((samples.size.toLong() + frameSize - 1L) / frameSize)
            .also { require(it <= Int.MAX_VALUE) { "PCM input is too large" } }
            .toInt()
        return DoubleArray(frameCount) { frame ->
            val start = frame.toLong() * frameSize
            val end = minOf(samples.size.toLong(), start + frameSize)
            var sumSquares = 0.0
            var containsNonFinite = false
            for (index in start until end) {
                val value = samples[index.toInt()].toDouble()
                if (value.isFinite()) {
                    sumSquares += value * value
                } else {
                    containsNonFinite = true
                }
            }
            if (containsNonFinite) {
                // Treat invalid PCM as non-silence so a decoder glitch cannot
                // turn into an automatic deletion.
                Double.POSITIVE_INFINITY
            } else {
                sqrt(sumSquares / (end - start).coerceAtLeast(1L))
            }
        }
    }

    private fun percentile(values: DoubleArray, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val index = ((sorted.lastIndex * fraction).toInt()).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
