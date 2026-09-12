package com.onetake.engine

/**
 * Records a [Filler] for each [FillerLexicon] match in committed segments and keeps a running count.
 *
 * Segments without word timings produce no fillers. A marker that ends a segment is decided when the
 * next segment arrives, so the fillers recorded across all segments equal [FillerLexicon.find] over
 * the whole transcript.
 */
class LiveFillerTracker(private val session: RecordingSession) : TranscriptListener {
    @Volatile
    var count: Int = 0
        private set

    /** The last decided word, as left context, followed by any words still awaiting a decision. */
    private var pending: List<TranscriptWord> = emptyList()
    private var pendingFrom = 0
    private var recordedThroughSample = 0L

    override fun onSegment(segment: TranscriptSegment) {
        val heardThrough = pending.lastOrNull()?.endSample
        // Words already heard are dropped so a re-delivered segment cannot record a filler twice.
        val fresh = FillerLexicon.inSourceOrder(segment.words)
            .filter { heardThrough == null || it.startSample >= heardThrough }
        if (fresh.isEmpty()) return

        val words = pending + fresh
        val scan = FillerLexicon.scan(words, SpeechSuggestionDetector.MIN_CONFIDENCE, pendingFrom)
        val keepFrom = (scan.resumeIndex - 1).coerceAtLeast(0)
        pending = words.subList(keepFrom, words.size).toList()
        pendingFrom = scan.resumeIndex - keepFrom

        scan.spans.forEach { span ->
            val match = span.match
            if (match.startSample < recordedThroughSample) return@forEach
            session.signal(Filler(fillerId(match), match.startSample, match.endSample, match.text))
            recordedThroughSample = match.endSample
            count++
        }
    }

    companion object {
        /** Derived from timing only, so the same filler always maps to the same session signal. */
        fun fillerId(match: FillerMatch): String = "filler-${match.startSample}-${match.endSample}"
    }
}
