package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseSilenceTest {
    private val frame = 512

    @Test fun silenceRestoresTheDetectedNonSpeechRunAndKeepsTheCandidateId() {
        // 40 speech frames, 100 non-speech frames (3.2 s), 40 speech frames.
        val activity = List(40) { SpeechActivity.SPEECH } + List(100) { SpeechActivity.NON_SPEECH } +
            List(40) { SpeechActivity.SPEECH }
        val candidate = StreamingPauseDetector(scripted(activity)).append(FloatArray(activity.size * frame)).single()

        val silence = candidate.toSilence(VoiceActivitySource.SILERO)

        assertEquals(Silence(candidate.id, 40L * frame, 140L * frame, VoiceActivitySource.SILERO), silence)
        assertTrue(silence.endSample - silence.startSample > StreamingPauseDetector.MIN_INTERIOR_SILENCE_MILLISECONDS * 16)
    }

    @Test fun sourceIsCarriedAndStartNeverPrecedesTheStream() {
        val silence = PauseCandidate("pause-1000-40000", 1_000L, 40_000L).toSilence(VoiceActivitySource.NEAR_SILENCE)
        assertEquals(Silence("pause-1000-40000", 0L, 43_200L, VoiceActivitySource.NEAR_SILENCE), silence)
        assertEquals(ClockDomain.RECOGNIZER, silence.liveClock)
    }

    private fun scripted(activity: List<SpeechActivity>) = object : SpeechFrameClassifier {
        private var index = 0
        override val frameSamples = frame
        override fun classify(samples: FloatArray) = activity[index++]
    }
}
