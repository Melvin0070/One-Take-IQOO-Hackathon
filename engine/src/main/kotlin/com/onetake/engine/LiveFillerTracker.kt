package com.onetake.engine

/** Records a [Filler] for each filler in a committed segment and keeps a running count. Stub until #47. */
class LiveFillerTracker(private val session: RecordingSession) : TranscriptListener {
    var count: Int = 0
        private set

    override fun onSegment(segment: TranscriptSegment) = Unit
}
