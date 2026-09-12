package com.onetake.engine

/**
 * The sample-index clock used by the engine.
 *
 * Audio samples are the only timeline values stored by the engine.  The
 * conversion methods deliberately use exact arithmetic so a bad duration or
 * anchor cannot silently wrap a Long and produce a corrupt edit list.
 */
class Timeline(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
    }

    /** Converts a non-negative whole-millisecond duration to samples. */
    fun samplesFromMillis(milliseconds: Long): Long {
        require(milliseconds >= 0L) { "Milliseconds must be non-negative" }
        val scaled = Math.multiplyExact(milliseconds, sampleRate.toLong())
        return scaled / MILLIS_PER_SECOND
    }

    /** Converts a non-negative sample position to whole milliseconds. */
    fun msFromSamples(samples: Long): Long {
        require(samples >= 0L) { "Samples must be non-negative" }
        val scaled = Math.multiplyExact(samples, MILLIS_PER_SECOND)
        return scaled / sampleRate.toLong()
    }

    /** Maps a sample position through the session's video anchor. */
    fun videoTimeUsFromSample(anchor: VideoAnchor, sampleIndex: Long): Long {
        require(sampleIndex >= 0L) { "Sample index must be non-negative" }
        val deltaSamples = Math.subtractExact(sampleIndex, anchor.sampleIndex)
        val deltaUs = Math.multiplyExact(deltaSamples, MICROSECONDS_PER_SECOND) /
            sampleRate.toLong()
        val mapped = Math.addExact(anchor.videoTimeUs, deltaUs)
        require(mapped >= 0L) { "Mapped video time must be non-negative" }
        return mapped
    }

    /** Maps a video timestamp back to the audio sample clock. */
    fun sampleFromVideoTimeUs(anchor: VideoAnchor, videoTimeUs: Long): Long {
        require(videoTimeUs >= 0L) { "Video time must be non-negative" }
        val deltaUs = Math.subtractExact(videoTimeUs, anchor.videoTimeUs)
        val deltaSamples = Math.multiplyExact(deltaUs, sampleRate.toLong()) /
            MICROSECONDS_PER_SECOND
        val mapped = Math.addExact(anchor.sampleIndex, deltaSamples)
        require(mapped >= 0L) { "Mapped sample index must be non-negative" }
        return mapped
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 16_000
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val MICROSECONDS_PER_SECOND: Long = 1_000_000L
    }
}

/** The one sample-to-video timebase pairing for a session. */
data class VideoAnchor(
    val sampleIndex: Long,
    val videoTimeUs: Long,
) {
    init {
        require(sampleIndex >= 0L) { "Anchor sample index must be non-negative" }
        require(videoTimeUs >= 0L) { "Anchor video time must be non-negative" }
    }
}
