package com.onetake.engine

/**
 * Finds conservative, interior pauses on a normalized 16 kHz PCM stream.
 *
 * Input is consumed as a stream of arbitrary-sized chunks.  Frames are kept
 * aligned to the session's sample zero, so splitting the same PCM at different
 * chunk boundaries produces the same candidates.
 */
class StreamingPauseDetector(
    private val speechFrameClassifier: SpeechFrameClassifier? = null,
) {
    private val samplesPerFrame = speechFrameClassifier?.frameSamples ?: FRAME_SAMPLES
    private var totalSamples = 0L
    private var frameSize = 0
    private var frameStartSample = 0L
    private var frameInvalid = false
    private var frameEnergy = 0.0
    private val frameSamples: FloatArray
    private var hasSpeechAnchor = false
    private var quietStartSample: Long? = null
    private var quietEndSample = 0L
    private var retainedQuietStartSample: Long? = null
    private var retainedQuietEndSample = 0L
    private var lastCandidateEndSample = 0L

    init {
        require(samplesPerFrame in 1..SAMPLE_RATE) {
            "Classifier frame size must be between 1 and $SAMPLE_RATE samples"
        }
        frameSamples = FloatArray(samplesPerFrame)
    }

    /** Appends normalized PCM and returns candidates closed by a speech anchor. */
    fun append(samples: FloatArray): List<PauseCandidate> {
        if (samples.isEmpty()) return emptyList()

        val candidates = ArrayList<PauseCandidate>()
        samples.forEach { value ->
            val sampleIndex = totalSamples
            totalSamples = Math.addExact(totalSamples, 1L)
            if (frameSize == 0) frameStartSample = sampleIndex

            if (!isValidPcm(value)) {
                frameInvalid = true
            } else {
                frameEnergy += value.toDouble() * value.toDouble()
            }
            frameSamples[frameSize] = value
            frameSize++

            if (frameSize == samplesPerFrame) {
                processFrame(candidates)
                frameSize = 0
                frameStartSample = totalSamples
                frameInvalid = false
                frameEnergy = 0.0
            }
        }
        return candidates
    }

    private fun processFrame(candidates: MutableList<PauseCandidate>) {
        if (frameInvalid) {
            resetRun()
            return
        }

        val activity = speechFrameClassifier?.let { classifier ->
            try {
                classifier.classify(frameSamples.copyOf())
            } catch (_: Throwable) {
                // A classifier failure makes the boundary untrusted. Drop the
                // anchor as well as the pending run so no cut can span it.
                resetRun()
                return
            }
        } ?: classifyWithRms()

        processActivity(activity, candidates)
    }

    private fun classifyWithRms(): SpeechActivity {
        val rms = kotlin.math.sqrt(frameEnergy / samplesPerFrame.toDouble())
        return when {
            rms <= NEAR_SILENCE_RMS -> SpeechActivity.NON_SPEECH
            rms >= SPEECH_ANCHOR_RMS -> SpeechActivity.SPEECH
            else -> SpeechActivity.UNKNOWN
        }
    }

    private fun processActivity(
        activity: SpeechActivity,
        candidates: MutableList<PauseCandidate>,
    ) {
        when (activity) {
            SpeechActivity.NON_SPEECH -> {
                if (quietStartSample == null && hasSpeechAnchor) {
                    quietStartSample = frameStartSample
                }
                if (quietStartSample != null) {
                    quietEndSample = Math.addExact(frameStartSample, samplesPerFrame.toLong())
                }
            }

            SpeechActivity.SPEECH -> {
                val quietRun = bestQuietRunForSpeechAnchor()
                val quietStart = quietRun?.startSample
                val quietEnd = quietRun?.endSample ?: 0L
                if (quietStart != null) {
                    val candidateStart = maxOf(
                        lastCandidateEndSample,
                        Math.addExact(quietStart, PADDING_SAMPLES),
                    )
                    val candidateEnd = quietEnd - PADDING_SAMPLES
                    if (candidateEnd > candidateStart) {
                        val candidate = PauseCandidate(
                            id = "pause-$candidateStart-$candidateEnd",
                            startSample = candidateStart,
                            endSample = candidateEnd,
                        )
                        candidates += candidate
                        lastCandidateEndSample = candidate.endSample
                    }
                }
                quietStartSample = null
                quietEndSample = 0L
                retainedQuietStartSample = null
                retainedQuietEndSample = 0L
                hasSpeechAnchor = true
            }

            SpeechActivity.UNKNOWN -> {
                if (speechFrameClassifier != null) {
                    retainCompletedQuietRun()
                }
                resetPendingQuietRun()
            }
        }
    }

    private fun bestQuietRunForSpeechAnchor(): QuietRun? {
        val retainedStart = retainedQuietStartSample
        var best = if (retainedStart != null) {
            QuietRun(retainedStart, retainedQuietEndSample)
        } else {
            null
        }
        val currentStart = quietStartSample
        if (currentStart != null && isQualifyingQuietRun(currentStart, quietEndSample)) {
            val current = QuietRun(currentStart, quietEndSample)
            if (best == null || isBetterQuietRun(current, best)) {
                best = current
            }
        }
        return best
    }

    private fun retainCompletedQuietRun() {
        val currentStart = quietStartSample ?: return
        if (!isQualifyingQuietRun(currentStart, quietEndSample)) return

        val current = QuietRun(currentStart, quietEndSample)
        val retainedStart = retainedQuietStartSample
        val retained = if (retainedStart != null) {
            QuietRun(retainedStart, retainedQuietEndSample)
        } else {
            null
        }
        if (retained == null || isBetterQuietRun(current, retained)) {
            retainedQuietStartSample = current.startSample
            retainedQuietEndSample = current.endSample
        }
    }

    private fun isQualifyingQuietRun(startSample: Long, endSample: Long): Boolean =
        endSample - startSample > MIN_INTERIOR_SILENCE_SAMPLES

    private fun isBetterQuietRun(candidate: QuietRun, existing: QuietRun): Boolean {
        val candidateDuration = candidate.endSample - candidate.startSample
        val existingDuration = existing.endSample - existing.startSample
        return candidateDuration > existingDuration ||
            (candidateDuration == existingDuration && candidate.startSample > existing.startSample)
    }

    private fun resetRun() {
        hasSpeechAnchor = false
        resetPendingQuietRun()
        retainedQuietStartSample = null
        retainedQuietEndSample = 0L
    }

    private fun resetPendingQuietRun() {
        quietStartSample = null
        quietEndSample = 0L
    }

    private fun isValidPcm(value: Float): Boolean =
        value.isFinite() && value >= -1f && value <= 1f

    private data class QuietRun(
        val startSample: Long,
        val endSample: Long,
    )

    companion object {
        const val SAMPLE_RATE: Int = 16_000
        const val FRAME_MILLISECONDS: Long = 20L
        const val FRAME_SAMPLES: Int = 320
        const val NEAR_SILENCE_RMS: Double = 0.0005
        const val SPEECH_ANCHOR_RMS: Double = 0.01
        const val MIN_INTERIOR_SILENCE_MILLISECONDS: Long = 1_200L
        const val PADDING_MILLISECONDS: Long = 200L

        private const val MIN_INTERIOR_SILENCE_SAMPLES: Long = 19_200L
        private const val PADDING_SAMPLES: Long = 3_200L
    }
}
