package com.example.one_take.editing

import com.example.one_take.engine.recordingTimeline
import com.onetake.engine.PauseCandidate
import com.onetake.engine.Silence
import com.onetake.engine.StreamingPauseDetector
import com.onetake.engine.VoiceActivitySource

/**
 * Conservatively promotes streaming pause candidates after saved-audio alignment.
 *
 * The recognizer's sample clock is not the finalized media clock, so a candidate
 * is eligible only when it stays inside a confirmed offline silence interval.
 */
internal object LivePauseReconciler {
    private const val OFFLINE_SILENCE_REASON = "silence"
    private const val LIVE_SILENCE_REASON = "silence (detected live)"
    private const val MIN_MATCH_MS = 200L

    /**
     * Maps live candidates into the saved-media timeline without widening them.
     * A missing alignment deliberately falls back to the saved-audio decision.
     */
    fun reconcile(
        candidates: List<PauseCandidate>,
        offsetMs: Long?,
        confirmed: EditDecision,
    ): EditDecision {
        if (offsetMs == null) return confirmed
        if (candidates.isEmpty() || confirmed.durationMs == 0L) {
            return emptyDecision(confirmed)
        }

        val confirmedSilences = confirmed.cuts
            .asSequence()
            .filter { it.enabled && it.reason == OFFLINE_SILENCE_REASON }
            .sortedWith(compareBy<EditCut> { it.startMs }.thenBy { it.endMs }.thenBy { it.id })
            .toList()
        if (confirmedSilences.isEmpty()) return emptyDecision(confirmed)

        val mappedCandidates = candidates
            .mapIndexedNotNull { ordinal, candidate ->
                mapCandidate(candidate, ordinal, offsetMs, confirmed.durationMs)
            }
            .sortedWith(
                compareBy<MappedCandidate> { it.startMs }
                    .thenBy { it.endMs }
                    .thenBy { it.ordinal },
            )

        val matches = ArrayList<RawMatch>()
        mappedCandidates.forEach { candidate ->
            val intersections = confirmedSilences.mapNotNull { reference ->
                val start = maxOf(candidate.startMs, reference.startMs)
                val end = minOf(candidate.endMs, reference.endMs)
                if (end - start >= MIN_MATCH_MS) start to end else null
            }
            intersections.forEachIndexed { intersectionIndex, interval ->
                val baseId = if (intersections.size == 1) {
                    candidate.id
                } else {
                    "${candidate.id}-${intersectionIndex + 1}"
                }
                matches += RawMatch(
                    id = baseId,
                    candidateOrdinal = candidate.ordinal,
                    intersectionIndex = intersectionIndex,
                    startMs = interval.first,
                    endMs = interval.second,
                )
            }
        }

        if (matches.isEmpty()) return emptyDecision(confirmed)

        // A valid engine plan cannot contain overlapping cuts.  Candidates from
        // the streaming detector are normally ordered and disjoint, but resolve
        // a malformed/replayed list defensively before constructing EditDecision.
        val usedIds = HashSet<String>(matches.size)
        val cuts = ArrayList<EditCut>(matches.size)
        var occupiedUntil = 0L
        var hasOccupiedInterval = false
        matches.sortedWith(
            compareBy<RawMatch> { it.startMs }
                .thenBy { it.endMs }
                .thenBy { it.candidateOrdinal }
                .thenBy { it.intersectionIndex },
        ).forEach { match ->
            val start = if (hasOccupiedInterval) maxOf(match.startMs, occupiedUntil) else match.startMs
            if (match.endMs - start < MIN_MATCH_MS) return@forEach

            val id = uniqueId(match.id, usedIds)
            cuts += EditCut(
                id = id,
                startMs = start,
                endMs = match.endMs,
                reason = LIVE_SILENCE_REASON,
                enabled = true,
            )
            occupiedUntil = match.endMs
            hasOccupiedInterval = true
        }

        return EditDecision(confirmed.durationMs, cuts)
    }

    /**
     * MEDIA-clock [Silence] copies of the pause cuts in a final [decision], keyed by cut id so a live
     * candidate and its confirmed copy share an id.
     *
     * Cuts keep speech padding inside the pause; a Silence reports the non-speech interval itself, as
     * the live copy does, so the padding is restored and bounded by the finalized media.
     */
    fun mediaSilences(
        decision: EditDecision,
        durationSamples: Long,
        source: VoiceActivitySource,
    ): List<Silence> = decision.cuts
        .filter { it.reason == OFFLINE_SILENCE_REASON || it.reason == LIVE_SILENCE_REASON }
        .mapNotNull { cut ->
            val padding = StreamingPauseDetector.PADDING_MILLISECONDS
            val start = recordingTimeline.samplesFromMillis((cut.startMs - padding).coerceAtLeast(0L))
            val end = minOf(durationSamples, recordingTimeline.samplesFromMillis(cut.endMs + padding))
            if (end > start) Silence(cut.id, start, end, source) else null
        }

    private fun mapCandidate(
        candidate: PauseCandidate,
        ordinal: Int,
        offsetMs: Long,
        durationMs: Long,
    ): MappedCandidate? {
        return try {
            val candidateStartMs = recordingTimeline.msFromSamples(candidate.startSample)
            val candidateEndMs = recordingTimeline.msFromSamples(candidate.endSample)
            val startMs = Math.addExact(candidateStartMs, offsetMs).coerceAtLeast(0L)
            val endMs = Math.addExact(candidateEndMs, offsetMs).coerceAtMost(durationMs)
            if (endMs <= startMs) null else MappedCandidate(
                id = candidate.id,
                ordinal = ordinal,
                startMs = startMs,
                endMs = endMs,
            )
        } catch (_: ArithmeticException) {
            // A malformed timestamp cannot be safely placed on the media clock.
            null
        }
    }

    private fun emptyDecision(confirmed: EditDecision): EditDecision =
        EditDecision(confirmed.durationMs, emptyList())

    private fun uniqueId(base: String, usedIds: MutableSet<String>): String {
        if (usedIds.add(base)) return base
        var suffix = 1L
        while (true) {
            val candidate = "$base-$suffix"
            if (usedIds.add(candidate)) return candidate
            suffix = Math.addExact(suffix, 1L)
        }
    }

    private data class MappedCandidate(
        val id: String,
        val ordinal: Int,
        val startMs: Long,
        val endMs: Long,
    )

    private data class RawMatch(
        val id: String,
        val candidateOrdinal: Int,
        val intersectionIndex: Int,
        val startMs: Long,
        val endMs: Long,
    )
}
