package com.onetake.engine

/** An unconfirmed pause interval in the 16 kHz RECOGNIZER clock, never a media cut. */
data class PauseCandidate(
    val id: String,
    val startSample: Long,
    val endSample: Long,
) {
    init {
        require(id.isNotBlank()) { "Pause candidate id must not be blank" }
        require(startSample >= 0L) { "Pause candidate start must be non-negative" }
        require(endSample > startSample) {
            "Pause candidate end must be after its start"
        }
    }
}

/**
 * The live [Silence] a streaming candidate was derived from, sharing its id.
 *
 * Candidates keep [StreamingPauseDetector.PADDING_MILLISECONDS] of quiet beside speech so they are
 * safe to cut. A Silence reports the observed non-speech interval, so that padding is restored and
 * the span stays longer than the detector's interior-pause threshold.
 */
fun PauseCandidate.toSilence(source: VoiceActivitySource): Silence {
    val padding = StreamingPauseDetector.PADDING_MILLISECONDS * StreamingPauseDetector.SAMPLE_RATE / 1_000L
    return Silence(id, (startSample - padding).coerceAtLeast(0L), Math.addExact(endSample, padding), source)
}
