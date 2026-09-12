package com.example.one_take.director

import com.example.one_take.captions.CaptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorSignalsTest {
    @Test
    fun explicitRepairMarkerProducesOneRetakePrompt() {
        val signals = DirectorSignals()
        val transcript = listOf(segment(0L, 1_000L, "That is the result, sorry, let me repeat it."))

        assertEquals(
            DirectorPrompt("self-repair", "Repeat that line", 1_000L),
            signals.observeTranscript(transcript, 1_000L),
        )
        assertNull(signals.observeTranscript(transcript, 1_500L))
        assertNull(signals.observeTranscript(transcript, 2_000L))
    }

    @Test
    fun revisedHypothesisDoesNotRepeatAnExistingMarkerButFindsNewMarker() {
        val signals = DirectorSignals()

        assertEquals(
            DirectorPrompt("self-repair", "Repeat that line", 1_000L),
            signals.observeTranscript(listOf(segment(0L, 1_000L, "The answer is sorry")), 1_000L),
        )
        assertNull(
            signals.observeTranscript(
                listOf(segment(0L, 1_200L, "The answer is sorry, and this is the revised tail")),
                2_000L,
            ),
        )
        assertEquals(
            DirectorPrompt("self-repair", "Repeat that line", 9_000L),
            signals.observeTranscript(
                listOf(
                    segment(0L, 1_200L, "The answer is sorry, and this is the revised tail"),
                    segment(8_000L, 9_000L, "I mean the second result"),
                ),
                9_000L,
            ),
        )
    }

    @Test
    fun repeatedWordsAndBigramsDoNotCreateRepairPrompt() {
        val signals = DirectorSignals()
        val transcript = listOf(segment(0L, 1_000L, "Very very good, this is the best best result"))

        assertNull(signals.observeTranscript(transcript, 1_000L))
    }

    @Test
    fun allExplicitMarkersAreRecognizedWithoutWordSubstringFalsePositives() {
        val signals = DirectorSignals()

        assertEquals(
            "Repeat that line",
            signals.observeTranscript(listOf(segment(0L, 1_000L, "Sorry!")), 1_000L)?.message,
        )
        assertNull(
            signals.observeTranscript(listOf(segment(2_000L, 3_000L, "unsorrying words")), 3_000L),
        )
        assertEquals(
            "Repeat that line",
            signals.observeTranscript(listOf(segment(9_000L, 10_000L, "मेरा मतलब यह है")), 10_000L)?.message,
        )
    }

    @Test
    fun quietAudioAloneAndUnknownConfidenceNeverPrompt() {
        val signals = DirectorSignals()

        assertNull(signals.observeAudio(confidence = 0.95f, rms = 0.001f, elapsedMs = 0L))
        assertNull(signals.observeAudio(confidence = null, rms = 0.001f, elapsedMs = 1_000L))
        assertNull(signals.observeAudio(confidence = null, rms = 0.001f, elapsedMs = 2_000L))
    }

    @Test
    fun lowConfidenceAndQuietAudioNeedTwoObservationsSpanningOneSecond() {
        val signals = DirectorSignals()

        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 0L))
        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 500L))
        assertEquals(
            DirectorPrompt("mumble", "Speak clearly", 1_000L),
            signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 1_000L),
        )
    }

    @Test
    fun aNonQualifyingAudioObservationResetsTheQuietStreak() {
        val signals = DirectorSignals()

        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 0L))
        assertNull(signals.observeAudio(confidence = 0.95f, rms = 0.002f, elapsedMs = 500L))
        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 1_000L))
        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 1_900L))
        assertEquals(
            "Speak clearly",
            signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 2_000L)?.message,
        )
    }

    @Test
    fun briefGazeDropIsIgnoredAndSustainedDropProducesOnePrompt() {
        val signals = DirectorSignals()

        assertNull(signals.observeGaze(offAxis = true, elapsedMs = 0L))
        assertNull(signals.observeGaze(offAxis = false, elapsedMs = 500L))
        assertNull(signals.observeGaze(offAxis = true, elapsedMs = 1_000L))
        assertNull(signals.observeGaze(offAxis = true, elapsedMs = 1_999L))
        assertEquals(
            DirectorPrompt("gaze", "Look toward the lens", 2_001L),
            signals.observeGaze(offAxis = true, elapsedMs = 2_001L),
        )
        assertNull(signals.observeGaze(offAxis = true, elapsedMs = 10_000L))
    }

    @Test
    fun timestampsNeverMoveBackwardsOrSatisfyAStreakEarly() {
        val signals = DirectorSignals()

        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 1_000L))
        assertNull(signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 500L))
        assertEquals(
            DirectorPrompt("mumble", "Speak clearly", 2_000L),
            signals.observeAudio(confidence = 0.30f, rms = 0.002f, elapsedMs = 2_000L),
        )
        assertTrue(signals.observeGaze(offAxis = true, elapsedMs = 1_000L) == null)
    }

    @Test
    fun resetStartsANewSession() {
        val signals = DirectorSignals()
        val marker = listOf(segment(0L, 1_000L, "sorry"))

        assertEquals("Repeat that line", signals.observeTranscript(marker, 1_000L)?.message)
        signals.reset()
        assertEquals("Repeat that line", signals.observeTranscript(marker, 1_000L)?.message)
    }

    private fun segment(startMs: Long, endMs: Long, text: String): CaptionSegment =
        CaptionSegment(startMs, endMs, text)
}
