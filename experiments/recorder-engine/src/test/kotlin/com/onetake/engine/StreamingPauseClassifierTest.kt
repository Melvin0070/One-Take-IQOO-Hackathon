package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPauseClassifierTest {
    @Test
    fun injectedClassifierCanMarkAboveNoiseFloorAsNonSpeech() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertEquals(
            listOf(PauseCandidate("pause-19200-44800", 19_200L, 44_800L)),
            detector.append(stream),
        )
    }

    @Test
    fun injectedClassifierProtectsSoftSpeechFromRmsThreshold() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, SOFT_SPEECH_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    @Test
    fun unknownFrameSplitsQuietRunWithoutLosingLeftSpeechAnchor() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertEquals(
            listOf(PauseCandidate("pause-51520-77120", 51_520L, 77_120L)),
            detector.append(stream),
        )
    }

    @Test
    fun longQuietRunBeforeUnknownIsPublishedAfterRightSpeechAnchor() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertEquals(
            listOf(PauseCandidate("pause-19200-44800", 19_200L, 44_800L)),
            detector.append(stream),
        )
    }

    @Test
    fun shortQuietRunsSeparatedByUnknownAreNotCombined() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 60, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 60, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    @Test
    fun retainedQuietRunNeedsRightSpeechAnchor() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    @Test
    fun longerQuietRunWinsWhenUnknownSeparatesTwoRuns() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 80, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertEquals(
            listOf(PauseCandidate("pause-19200-44800", 19_200L, 44_800L)),
            detector.append(stream),
        )
    }

    @Test
    fun laterLongerQuietRunWinsWhenUnknownSeparatesTwoRuns() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 80, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertEquals(
            listOf(PauseCandidate("pause-45120-70720", 45_120L, 70_720L)),
            detector.append(stream),
        )
    }

    @Test
    fun invalidFrameAfterRetainedQuietRunDiscardsRunAndAnchor() {
        val detector = StreamingPauseDetector(classifierForSamples())
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val invalid = pcm(frame, ABOVE_NOISE_FLOOR_LEVEL).also { it[17] = Float.NaN }
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            invalid,
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    @Test
    fun classifierFailureAfterRetainedQuietRunDiscardsRunAndAnchor() {
        val detector = StreamingPauseDetector(
            SpeechFrameClassifier { samples ->
                if (samples.first() == CLASSIFIER_FAILURE_LEVEL) {
                    error("classifier failure")
                }
                activityFor(samples)
            },
        )
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame, CLASSIFIER_FAILURE_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    @Test
    fun retainedQuietRunIsChunkInvariant() {
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, UNKNOWN_LEVEL),
            pcm(frame * 80, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        val wholeChunk = StreamingPauseDetector(classifierForSamples()).append(stream)
        val splitChunks = appendInChunks(
            samples = stream,
            sizes = intArrayOf(1, 17, 319, 7, 1_003, 64, 2_509),
        )

        assertEquals(wholeChunk, splitChunks)
    }

    @Test
    fun invalidFrameResetsAnchorAndDoesNotInvokeClassifier() {
        val classifiedFrames = ArrayList<Float>()
        var invalidFrameClassified = false
        val detector = StreamingPauseDetector(
            SpeechFrameClassifier { samples ->
                if (samples.any { it.isNaN() }) invalidFrameClassified = true
                classifiedFrames += samples.first()
                activityFor(samples)
            },
        )
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val invalid = pcm(frame, ABOVE_NOISE_FLOOR_LEVEL).also { it[17] = Float.NaN }
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            invalid,
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
        assertEquals(300, classifiedFrames.size)
        assertTrue(!invalidFrameClassified)
    }

    @Test
    fun injectedClassifierIsChunkInvariant() {
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        val wholeChunk = StreamingPauseDetector(classifierForSamples()).append(stream)
        val splitChunks = appendInChunks(stream, intArrayOf(1, 17, 319, 7, 1_003, 64, 2_509))

        assertEquals(wholeChunk, splitChunks)
    }

    @Test
    fun classifierFrameSizeCanBe512AndUsesSampleAccurateThresholdAndPadding() {
        val frame = 512
        val streamBelowThreshold = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 37, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )
        val streamAboveThreshold = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 38, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(StreamingPauseDetector(classifierForSamples(frame)).append(streamBelowThreshold).isEmpty())
        assertEquals(
            listOf(PauseCandidate("pause-28800-41856", 28_800L, 41_856L)),
            StreamingPauseDetector(classifierForSamples(frame)).append(streamAboveThreshold),
        )
    }

    @Test
    fun classifierWith512SamplesIsChunkInvariant() {
        val frame = 512
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 38, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        val wholeChunk = StreamingPauseDetector(classifierForSamples(frame)).append(stream)
        val splitChunks = appendInChunks(
            samples = stream,
            sizes = intArrayOf(1, 511, 7, 1_003, 64, 2_509),
            classifier = classifierForSamples(frame),
        )

        assertEquals(wholeChunk, splitChunks)
    }

    @Test
    fun classifierFrameSizeMustBePositiveAndBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingPauseDetector(classifierForSamples(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingPauseDetector(classifierForSamples(StreamingPauseDetector.SAMPLE_RATE + 1))
        }
    }

    @Test
    fun classifierFailureResetsAnchorAndCannotCreateCutAcrossFailure() {
        val detector = StreamingPauseDetector(
            SpeechFrameClassifier { samples ->
                if (samples.first() == CLASSIFIER_FAILURE_LEVEL) {
                    error("classifier failure")
                }
                activityFor(samples)
            },
        )
        val frame = StreamingPauseDetector.FRAME_SAMPLES
        val stream = concat(
            pcm(frame * 50, SPEECH_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame, CLASSIFIER_FAILURE_LEVEL),
            pcm(frame * 100, ABOVE_NOISE_FLOOR_LEVEL),
            pcm(frame * 50, SPEECH_LEVEL),
        )

        assertTrue(detector.append(stream).isEmpty())
    }

    private fun classifierForSamples(frameSamples: Int = StreamingPauseDetector.FRAME_SAMPLES): SpeechFrameClassifier =
        object : SpeechFrameClassifier {
            override val frameSamples: Int = frameSamples

            override fun classify(samples: FloatArray): SpeechActivity {
                assertEquals(frameSamples, samples.size)
                return activityFor(samples)
            }
        }

    private fun activityFor(samples: FloatArray): SpeechActivity = when (samples.first()) {
        SPEECH_LEVEL, SOFT_SPEECH_LEVEL -> SpeechActivity.SPEECH
        ABOVE_NOISE_FLOOR_LEVEL -> SpeechActivity.NON_SPEECH
        UNKNOWN_LEVEL -> SpeechActivity.UNKNOWN
        else -> error("unexpected frame marker ${samples.first()}")
    }

    private fun appendInChunks(
        samples: FloatArray,
        sizes: IntArray,
        classifier: SpeechFrameClassifier = classifierForSamples(),
    ): List<PauseCandidate> {
        val detector = StreamingPauseDetector(classifier)
        val candidates = ArrayList<PauseCandidate>()
        var offset = 0
        var sizeIndex = 0
        while (offset < samples.size) {
            val requested = sizes[sizeIndex % sizes.size]
            val end = minOf(samples.size, offset + requested)
            candidates += detector.append(samples.copyOfRange(offset, end))
            offset = end
            sizeIndex++
        }
        return candidates
    }

    private fun pcm(count: Int, value: Float): FloatArray = FloatArray(count) { value }

    private fun concat(vararg chunks: FloatArray): FloatArray {
        val total = chunks.sumOf { it.size }
        val result = FloatArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    companion object {
        private const val SPEECH_LEVEL = 0.1f
        private const val SOFT_SPEECH_LEVEL = 0.002f
        private const val ABOVE_NOISE_FLOOR_LEVEL = 0.004f
        private const val UNKNOWN_LEVEL = 0.006f
        private const val CLASSIFIER_FAILURE_LEVEL = 0.008f
    }
}
