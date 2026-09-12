package com.example.one_take.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioAlignmentTest {
    @Test
    fun findsPositiveOffsetWithGainChange() {
        val referenceEnvelope = speechEnvelope(seed = 7, frameCount = 600)
        val reference = pcm(referenceEnvelope)
        val live = pcm(referenceEnvelope.copyOfRange(23, 523), gain = 0.35f)

        assertEquals(230L, AudioAlignment.offsetMs(reference, live))
    }

    @Test
    fun findsNegativeOffset() {
        val source = speechEnvelope(seed = 11, frameCount = 620)
        val reference = pcm(source.copyOfRange(20, 520))
        val live = pcm(source.copyOfRange(0, 500), gain = 1.7f)

        assertEquals(-200L, AudioAlignment.offsetMs(reference, live))
    }

    @Test
    fun alignsDespiteAnUnsharedMicrophoneStartupTransient() {
        val source = speechEnvelope(seed = 11, frameCount = 620)
        val reference = pcm(source.copyOfRange(50, 550))
        val liveEnvelope = source.copyOfRange(0, 500)
        liveEnvelope[1] = 0.3f
        assertEquals(-500L, AudioAlignment.offsetMs(reference, pcm(liveEnvelope)))
    }

    @Test
    fun rejectsSilence() {
        val silence = FloatArray(16_000 * 4)

        assertNull(AudioAlignment.offsetMs(silence, silence))
    }

    @Test
    fun rejectsCoincidentUnrelatedShortBurstsSurroundedBySilence() {
        val reference = speechEnvelope(3, 600)
        val unrelated = speechEnvelope(91, 600)
        for (index in reference.indices) {
            if (index !in 60..79) { reference[index] = 0f; unrelated[index] = 0f }
        }
        assertNull(AudioAlignment.offsetMs(pcm(reference), pcm(unrelated)))
    }

    @Test
    fun rejectsUnrelatedAudio() {
        val reference = pcm(speechEnvelope(seed = 3, frameCount = 500))
        val unrelated = pcm(speechEnvelope(seed = 91, frameCount = 500), gain = 0.8f)

        assertNull(AudioAlignment.offsetMs(reference, unrelated))
    }

    private fun pcm(envelope: FloatArray, gain: Float = 1f): FloatArray =
        FloatArray(envelope.size * 160) { sampleIndex ->
            envelope[sampleIndex / 160] * gain
        }

    private fun speechEnvelope(seed: Int, frameCount: Int): FloatArray {
        return FloatArray(frameCount) { index ->
            val speech = index in 60 until 470
            if (!speech) {
                0f
            } else {
                var value = seed.toLong() * 0x9E37_79B9L + index.toLong() * 0xC2B2_AE3DL
                value = value xor (value ushr 29)
                value *= 0x1656_67B1L
                value = value xor (value ushr 31)
                val unit = (value and 0xFFFFL).toFloat() / 65_535f
                0.06f + 0.42f * unit
            }
        }
    }
}
