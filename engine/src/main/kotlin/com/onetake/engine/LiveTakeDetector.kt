package com.onetake.engine

/** Groups repeated attempts at the same content into [TakeAttempt] signals. Stub until #49. */
class LiveTakeDetector(private val session: RecordingSession, private val mode: SessionMode) : TranscriptListener {
    override fun onSegment(segment: TranscriptSegment) = Unit

    /** Script Mode ground truth: call after the script matcher has consumed [segment]. */
    fun onScriptProgress(segment: TranscriptSegment, progress: ScriptProgress) = Unit
}
