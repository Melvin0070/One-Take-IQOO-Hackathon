package com.example.one_take.vision

/**
 * A single face observation in the upright camera coordinate space.
 *
 * Coordinates and dimensions are normalized to [0, 1]. [timestampMs] uses
 * elapsed realtime, so it can be compared with CameraRecorder's recording
 * start timestamp without depending on wall-clock changes.
 */
internal data class FaceObservation(
    val timestampMs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val faceLuminance: Float,
    val backgroundLuminance: Float,
    /** A coarse orientation hint for setup/director coaching. */
    val offAxis: Boolean
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must use elapsed realtime" }
        require(centerX in 0f..1f) { "centerX must be normalized" }
        require(centerY in 0f..1f) { "centerY must be normalized" }
        require(width in 0f..1f) { "width must be normalized" }
        require(height in 0f..1f) { "height must be normalized" }
        require(faceLuminance in 0f..1f) { "faceLuminance must be normalized" }
        require(backgroundLuminance in 0f..1f) {
            "backgroundLuminance must be normalized"
        }
    }
}
