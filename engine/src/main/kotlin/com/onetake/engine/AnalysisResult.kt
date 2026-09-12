package com.onetake.engine

enum class RemovalKind { LOSING_TAKE, LONG_SILENCE, FILLER_CLUSTER, INCOMPLETE_SENTENCE }

/** A recommended removal. Nothing is cut until the user accepts it in the editor. */
data class RemovalCandidate(
    val id: String,
    val startSample: Long,
    val endSample: Long,
    val kind: RemovalKind,
    val reason: String,
)

/** The best attempt of a [TakeAttempt] group and the losing attempts as removal candidates. */
data class TakeRecommendation(
    val groupId: String,
    val recommended: TakeAttempt,
    val losing: List<RemovalCandidate>,
)

/** Post-recording recommendations, in [clock] samples. */
data class AnalysisResult(
    val sessionId: String,
    val clock: ClockDomain,
    val takes: List<TakeRecommendation>,
    val removals: List<RemovalCandidate>,
)

/** Stub until #51. */
object SessionAnalysis {
    /**
     * [liveToMediaOffsetSamples] maps live (recognizer) spans onto the finalized recording; null
     * when the microphone could not be aligned, in which case results stay in the live clock.
     */
    fun analyze(session: RecordingSessionSnapshot, liveToMediaOffsetSamples: Long? = null): AnalysisResult =
        AnalysisResult(session.sessionId, ClockDomain.RECOGNIZER, emptyList(), emptyList())
}
