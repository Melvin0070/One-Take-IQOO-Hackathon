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
