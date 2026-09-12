package com.example.one_take.editing

import com.onetake.engine.PauseCandidate
import com.onetake.engine.Silence
import com.onetake.engine.VoiceActivitySource
import com.onetake.engine.toSilence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePauseReconcilerTest {
    @Test
    fun positiveAlignmentMapsCandidatesIntoConfirmedSilenceCuts() {
        val confirmed = decision(
            EditCut("offline", 3_000L, 5_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("live", 40_000L, 72_000L)),
            offsetMs = 500L,
            confirmed = confirmed,
        )

        assertEquals(
            listOf(EditCut("live", 3_000L, 5_000L, "silence (detected live)")),
            reconciled.cuts,
        )
    }

    @Test
    fun negativeAlignmentMapsCandidatesBackIntoTheMediaClock() {
        val confirmed = decision(
            EditCut("offline", 1_000L, 3_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("live", 24_000L, 56_000L)),
            offsetMs = -500L,
            confirmed = confirmed,
        )

        assertEquals(
            listOf(EditCut("live", 1_000L, 3_000L, "silence (detected live)")),
            reconciled.cuts,
        )
    }

    @Test
    fun candidateIntervalsAreClippedAndNeverWidenedByReferenceCuts() {
        val confirmed = decision(
            EditCut("offline", 500L, 2_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("live", 8_000L, 40_000L)),
            offsetMs = -1_000L,
            confirmed = confirmed,
        )

        assertEquals(
            listOf(EditCut("live", 500L, 1_500L, "silence (detected live)")),
            reconciled.cuts,
        )
    }

    @Test
    fun oneCandidateCrossingTwoConfirmedPausesGetsStableSuffixesInTimeOrder() {
        val confirmed = decision(
            EditCut("offline-one", 3_000L, 4_000L, "silence"),
            EditCut("offline-two", 5_000L, 6_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("live", 32_000L, 128_000L)),
            offsetMs = 1_000L,
            confirmed = confirmed,
        )

        assertEquals(
            listOf(
                EditCut("live-1", 3_000L, 4_000L, "silence (detected live)"),
                EditCut("live-2", 5_000L, 6_000L, "silence (detected live)"),
            ),
            reconciled.cuts,
        )
    }

    @Test
    fun unrelatedCandidateAndNonDetectorCutAreNotPromoted() {
        val confirmed = EditDecision(
            durationMs = 10_000L,
            cuts = listOf(EditCut("manual", 4_000L, 6_000L, "manual")),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("noise", 0L, 32_000L)),
            offsetMs = 0L,
            confirmed = confirmed,
        )

        assertTrue(reconciled.cuts.isEmpty())
    }

    @Test
    fun subThresholdIntersectionIsRejectedAsAFalseCandidate() {
        val confirmed = decision(
            EditCut("offline", 4_000L, 5_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("noise", 62_400L, 64_000L)),
            offsetMs = 100L,
            confirmed = confirmed,
        )

        assertTrue(reconciled.cuts.isEmpty())
    }

    @Test
    fun missingAlignmentReturnsConfirmedDecisionAsSavedAudioFallback() {
        val confirmed = decision(
            EditCut("offline", 4_000L, 5_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("live", 0L, 160_000L)),
            offsetMs = null,
            confirmed = confirmed,
        )

        assertSame(confirmed, reconciled)
    }

    @Test
    fun validAlignmentWithNoCandidatesProducesNoCuts() {
        val confirmed = decision(
            EditCut("offline", 4_000L, 5_000L, "silence"),
        )

        val reconciled = LivePauseReconciler.reconcile(
            candidates = emptyList(),
            offsetMs = 0L,
            confirmed = confirmed,
        )

        assertEquals(EditDecision(10_000L, emptyList()), reconciled)
    }

    @Test
    fun candidatesAreSortedAndOverlapsAreBoundedWithoutMutatingConfirmedDecision() {
        val confirmed = decision(
            EditCut("offline", 2_000L, 4_000L, "silence"),
            EditCut("offline-two", 4_500L, 6_000L, "silence"),
        )
        val original = confirmed

        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(
                candidate("later", 72_000L, 104_000L),
                candidate("earlier", 24_000L, 56_000L),
            ),
            offsetMs = 500L,
            confirmed = confirmed,
        )

        assertEquals(
            listOf(
                EditCut("earlier", 2_000L, 4_000L, "silence (detected live)"),
                EditCut("later", 5_000L, 6_000L, "silence (detected live)"),
            ),
            reconciled.cuts,
        )
        assertSame(original, confirmed)
        assertEquals(
            listOf(
                EditCut("offline", 2_000L, 4_000L, "silence"),
                EditCut("offline-two", 4_500L, 6_000L, "silence"),
            ),
            confirmed.cuts,
        )
    }

    @Test
    fun overflowingSampleOrOffsetIsIgnoredSafely() {
        val confirmed = decision(
            EditCut("offline", 4_000L, 5_000L, "silence"),
        )

        val overflowingSample = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("sample-overflow", Long.MAX_VALUE - 1L, Long.MAX_VALUE)),
            offsetMs = 0L,
            confirmed = confirmed,
        )
        val overflowingOffset = LivePauseReconciler.reconcile(
            candidates = listOf(candidate("offset-overflow", 64_000L, 80_000L)),
            offsetMs = Long.MAX_VALUE,
            confirmed = confirmed,
        )

        assertTrue(overflowingSample.cuts.isEmpty())
        assertTrue(overflowingOffset.cuts.isEmpty())
    }

    @Test
    fun mediaSilencesRestorePaddingKeepCutIdsAndSkipOtherCuts() {
        val final = decision(
            EditCut("pause-a", 3_000L, 5_000L, "silence (detected live)"),
            EditCut("stumble", 6_000L, 6_500L, "repeated phrase"),
            EditCut("silence-7000-8000", 7_000L, 8_000L, "silence", enabled = false),
        )

        assertEquals(
            listOf(
                Silence("pause-a", 44_800L, 83_200L, VoiceActivitySource.WEBRTC),
                Silence("silence-7000-8000", 108_800L, 131_200L, VoiceActivitySource.WEBRTC),
            ),
            LivePauseReconciler.mediaSilences(final, 160_000L, VoiceActivitySource.WEBRTC),
        )
    }

    @Test
    fun mediaSilencesNeverLeaveTheFinalizedMedia() {
        val final = decision(EditCut("head", 100L, 1_000L, "silence"), EditCut("tail", 9_000L, 9_900L, "silence"))

        assertEquals(
            listOf(
                Silence("head", 0L, 19_200L, VoiceActivitySource.SILERO),
                Silence("tail", 140_800L, 159_000L, VoiceActivitySource.SILERO),
            ),
            LivePauseReconciler.mediaSilences(final, 159_000L, VoiceActivitySource.SILERO),
        )
    }

    @Test
    fun confirmedMediaSilenceMatchesItsLiveCopyWhenClocksAgree() {
        val candidate = candidate("pause-41600-80000", 41_600L, 80_000L)
        val reconciled = LivePauseReconciler.reconcile(
            candidates = listOf(candidate),
            offsetMs = 0L,
            confirmed = decision(EditCut("offline", 2_000L, 6_000L, "silence")),
        )

        val live = candidate.toSilence(VoiceActivitySource.SILERO)
        assertEquals(
            listOf(live),
            LivePauseReconciler.mediaSilences(reconciled, 160_000L, VoiceActivitySource.SILERO),
        )
    }

    private fun decision(vararg cuts: EditCut): EditDecision = EditDecision(
        durationMs = 10_000L,
        cuts = cuts.toList(),
    )

    private fun candidate(id: String, startSample: Long, endSample: Long): PauseCandidate =
        PauseCandidate(id = id, startSample = startSample, endSample = endSample)
}
