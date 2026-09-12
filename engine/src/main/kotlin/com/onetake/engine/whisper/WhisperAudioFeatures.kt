package com.onetake.engine.whisper

import java.util.concurrent.CancellationException
import kotlin.math.max

/**
 * Computes the input tensor used by the Qualcomm Whisper Tiny encoder.
 *
 * The input is mono PCM sampled at 16 kHz with values in the usual [-1, 1]
 * range.  The returned array is channel-major: the first 3,000 values are
 * mel channel 0, the next 3,000 values are mel channel 1, and so on.  It has
 * exactly [MEL_BINS] * [FRAME_COUNT] values and can therefore be copied into
 * the encoder's [1, 80, 3000] FP16 input after conversion to that type.
 *
 * Input shorter than 30 seconds is right-padded with zero samples before the
 * centered STFT.  Input longer than 30 seconds is truncated to the first
 * [MAX_AUDIO_SAMPLES] samples.  This fixed-size behavior matches the
 * Hugging Face Whisper feature extractor used to produce the Qualcomm graph.
 * An empty input is treated as 30 seconds of silence.  The [isCancelled]
 * callback is checked before extraction and once for every STFT frame; a
 * cancellation throws [CancellationException].
 */
object WhisperAudioFeatures {
    const val SAMPLE_RATE: Int = 16_000
    const val FFT_SIZE: Int = 400
    const val HOP_LENGTH: Int = 160
    const val MEL_BINS: Int = 80
    const val MAX_AUDIO_SECONDS: Int = 30
    const val MAX_AUDIO_SAMPLES: Int = SAMPLE_RATE * MAX_AUDIO_SECONDS
    const val FRAME_COUNT: Int = MAX_AUDIO_SAMPLES / HOP_LENGTH
    const val FEATURE_COUNT: Int = MEL_BINS * FRAME_COUNT

    private const val CENTER_PADDING: Int = FFT_SIZE / 2
    private const val FREQUENCY_BINS: Int = FFT_SIZE / 2 + 1
    private const val MEL_FLOOR: Double = 1e-10
    private const val MAX_HZ: Double = SAMPLE_RATE / 2.0
    private const val TWO_PI: Double = 2.0 * Math.PI

    private val hannWindow: DoubleArray = DoubleArray(FFT_SIZE) { index ->
        0.5 * (1.0 - Math.cos(TWO_PI * index / FFT_SIZE.toDouble()))
    }

    /** Mel filters in [mel][frequency-bin] order. */
    private val melFilters: DoubleArray = createSlaneyMelFilters()
    private val fftPlan = FftPlan(FFT_SIZE)

    /**
     * Extracts normalized log-mel features for one mono 16 kHz waveform.
     *
     * The output uses Whisper's `log10`, 80 dB dynamic-range clamp, and
     * `(log10(mel) + 4) / 4` normalization.  This is the same layout and
     * numerical convention as `WhisperFeatureExtractor`'s `input_features`.
     */
    @JvmStatic
    fun extract(samples: FloatArray, isCancelled: () -> Boolean = { false }): FloatArray {
        checkCancellation(isCancelled)
        val copiedSamples = minOf(samples.size, MAX_AUDIO_SAMPLES)
        for (index in 0 until copiedSamples) {
            require(samples[index].isFinite()) { "PCM samples must be finite" }
        }

        val padded = FloatArray(MAX_AUDIO_SAMPLES + CENTER_PADDING * 2)
        samples.copyInto(
            destination = padded,
            destinationOffset = CENTER_PADDING,
            startIndex = 0,
            endIndex = copiedSamples,
        )

        // np.pad(..., mode="reflect") mirrors samples without repeating the
        // edge value.  The right side must also be reflected when the input
        // fills the complete 30-second window; for a shorter input those
        // source samples are the zeros used for right-padding.
        for (index in 0 until CENTER_PADDING) {
            padded[CENTER_PADDING - 1 - index] = padded[CENTER_PADDING + index + 1]
            padded[CENTER_PADDING + MAX_AUDIO_SAMPLES + index] =
                padded[CENTER_PADDING + MAX_AUDIO_SAMPLES - 2 - index]
        }

        val output = FloatArray(FEATURE_COUNT)
        val frame = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)

        for (frameIndex in 0 until FRAME_COUNT) {
            checkCancellation(isCancelled)
            val frameOffset = frameIndex * HOP_LENGTH
            for (sampleIndex in 0 until FFT_SIZE) {
                frame[sampleIndex] =
                    padded[frameOffset + sampleIndex].toDouble() * hannWindow[sampleIndex]
                imaginary[sampleIndex] = 0.0
            }

            fftPlan.transform(frame, imaginary)
            for (melIndex in 0 until MEL_BINS) {
                var energy = 0.0
                val filterOffset = melIndex * FREQUENCY_BINS
                for (frequencyIndex in 0 until FREQUENCY_BINS) {
                    val real = frame[frequencyIndex]
                    val imag = imaginary[frequencyIndex]
                    val magnitudeSquared = real * real + imag * imag
                    energy += magnitudeSquared * melFilters[filterOffset + frequencyIndex]
                }
                val flooredEnergy = max(MEL_FLOOR, energy)
                output[melIndex * FRAME_COUNT + frameIndex] =
                    Math.log10(flooredEnergy).toFloat()
            }
        }

        var maximum = -Float.MAX_VALUE
        for (value in output) {
            if (value > maximum) {
                maximum = value
            }
        }
        val minimum = maximum - 8.0f
        for (index in output.indices) {
            output[index] = (max(output[index], minimum) + 4.0f) / 4.0f
        }
        return output
    }

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw CancellationException("Whisper audio feature extraction cancelled")
        }
    }

    private fun createSlaneyMelFilters(): DoubleArray {
        val melPoints = DoubleArray(MEL_BINS + 2)
        val minMel = hertzToMel(0.0)
        val maxMel = hertzToMel(MAX_HZ)
        for (index in melPoints.indices) {
            melPoints[index] = minMel + (maxMel - minMel) * index / (MEL_BINS + 1).toDouble()
        }

        val filterPoints = DoubleArray(melPoints.size) { index -> melToHertz(melPoints[index]) }
        val differences = DoubleArray(filterPoints.size - 1) { index ->
            filterPoints[index + 1] - filterPoints[index]
        }
        val filters = DoubleArray(MEL_BINS * FREQUENCY_BINS)
        for (melIndex in 0 until MEL_BINS) {
            // Slaney area normalization divides by the width of each full
            // triangular band and multiplies by two.
            val areaNormalization = 2.0 / (filterPoints[melIndex + 2] - filterPoints[melIndex])
            val filterOffset = melIndex * FREQUENCY_BINS
            for (frequencyIndex in 0 until FREQUENCY_BINS) {
                val frequency = frequencyIndex * SAMPLE_RATE / (2.0 * (FREQUENCY_BINS - 1))
                val downSlope =
                    (frequency - filterPoints[melIndex]) / differences[melIndex]
                val upSlope =
                    (filterPoints[melIndex + 2] - frequency) / differences[melIndex + 1]
                filters[filterOffset + frequencyIndex] =
                    max(0.0, minOf(downSlope, upSlope)) * areaNormalization
            }
        }
        return filters
    }

    private fun hertzToMel(frequency: Double): Double {
        return if (frequency < 1_000.0) {
            3.0 * frequency / 200.0
        } else {
            15.0 + Math.log(frequency / 1_000.0) * (27.0 / Math.log(6.4))
        }
    }

    private fun melToHertz(mel: Double): Double {
        return if (mel < 15.0) {
            200.0 * mel / 3.0
        } else {
            1_000.0 * Math.exp((Math.log(6.4) / 27.0) * (mel - 15.0))
        }
    }

    /**
     * An in-place mixed-radix decimation-in-frequency FFT.
     *
     * A 400-point transform is required by Whisper, so a 512-point radix-2
     * transform would change the frequency bins.  Factoring 400 as
     * 5 * 5 * 2 * 2 * 2 * 2 keeps the transform O(N log N) without using a
     * slow O(N²) DFT or changing the encoder's expected mel filters.
     */
    private class FftPlan(private val size: Int) {
        private val radices: IntArray
        private val stages: Array<Stage>
        private val permutation: IntArray

        init {
            val factors = ArrayList<Int>()
            var remainder = size
            while (remainder % 5 == 0) {
                factors += 5
                remainder /= 5
            }
            while (remainder > 1) {
                require(remainder % 2 == 0) { "Unsupported FFT size: $size" }
                factors += 2
                remainder /= 2
            }
            radices = factors.toIntArray()
            stages = Array(radices.size) { stageIndex ->
                val span = radices.copyOfRange(stageIndex, radices.size)
                    .fold(1) { product, radix -> product * radix }
                Stage(span, radices[stageIndex])
            }
            permutation = createPermutation()
        }

        fun transform(real: DoubleArray, imaginary: DoubleArray) {
            require(real.size == size && imaginary.size == size) {
                "FFT buffers must contain exactly $size samples"
            }
            val sourceReal = DoubleArray(5)
            val sourceImaginary = DoubleArray(5)
            var span = size
            var stageIndex = 0
            while (span > 1) {
                val radix = radices[stageIndex]
                val subTransformSize = span / radix
                val stage = stages[stageIndex]
                var blockOffset = 0
                while (blockOffset < size) {
                    for (index in 0 until subTransformSize) {
                        for (digit in 0 until radix) {
                            val sourceIndex = blockOffset + index + digit * subTransformSize
                            sourceReal[digit] = real[sourceIndex]
                            sourceImaginary[digit] = imaginary[sourceIndex]
                        }
                        for (outputDigit in 0 until radix) {
                            var combinedReal = 0.0
                            var combinedImaginary = 0.0
                            for (digit in 0 until radix) {
                                val rootReal = stage.rootReal[outputDigit * radix + digit]
                                val rootImaginary = stage.rootImaginary[outputDigit * radix + digit]
                                combinedReal +=
                                    sourceReal[digit] * rootReal - sourceImaginary[digit] * rootImaginary
                                combinedImaginary +=
                                    sourceReal[digit] * rootImaginary + sourceImaginary[digit] * rootReal
                            }
                            val twiddleReal = stage.twiddleReal[index * radix + outputDigit]
                            val twiddleImaginary = stage.twiddleImaginary[index * radix + outputDigit]
                            val destinationIndex =
                                blockOffset + index + outputDigit * subTransformSize
                            real[destinationIndex] =
                                combinedReal * twiddleReal - combinedImaginary * twiddleImaginary
                            imaginary[destinationIndex] =
                                combinedReal * twiddleImaginary + combinedImaginary * twiddleReal
                        }
                    }
                    blockOffset += span
                }
                span = subTransformSize
                stageIndex += 1
            }

            // DIF stages leave the frequency digits reversed.  The mapping is
            // generated from the same factorization so this remains correct
            // if the transform size is changed to another supported factor.
            val reorderedReal = DoubleArray(size)
            val reorderedImaginary = DoubleArray(size)
            for (storedIndex in 0 until size) {
                val naturalIndex = permutation[storedIndex]
                reorderedReal[naturalIndex] = real[storedIndex]
                reorderedImaginary[naturalIndex] = imaginary[storedIndex]
            }
            reorderedReal.copyInto(real)
            reorderedImaginary.copyInto(imaginary)
        }

        private fun createPermutation(): IntArray {
            val result = IntArray(size)
            val reversedRadices = radices.reversedArray()
            var storedIndex = 0
            while (storedIndex < size) {
                var remainder = storedIndex
                val digits = IntArray(radices.size)
                for (stageIndex in radices.indices.reversed()) {
                    val radix = radices[stageIndex]
                    digits[stageIndex] = remainder % radix
                    remainder /= radix
                }
                var naturalIndex = 0
                var multiplier = 1
                for (digitIndex in digits.indices) {
                    naturalIndex += digits[digitIndex] * multiplier
                    multiplier *= reversedRadices[digits.size - 1 - digitIndex]
                }
                result[storedIndex] = naturalIndex
                storedIndex += 1
            }
            return result
        }

        private class Stage(span: Int, radix: Int) {
            val rootReal = DoubleArray(radix * radix)
            val rootImaginary = DoubleArray(radix * radix)
            val twiddleReal = DoubleArray((span / radix) * radix)
            val twiddleImaginary = DoubleArray((span / radix) * radix)

            init {
                for (outputDigit in 0 until radix) {
                    for (digit in 0 until radix) {
                        val angle = -TWO_PI * outputDigit * digit / radix.toDouble()
                        rootReal[outputDigit * radix + digit] = Math.cos(angle)
                        rootImaginary[outputDigit * radix + digit] = Math.sin(angle)
                    }
                }
                for (index in 0 until span / radix) {
                    for (outputDigit in 0 until radix) {
                        val angle = -TWO_PI * index * outputDigit / span.toDouble()
                        twiddleReal[index * radix + outputDigit] = Math.cos(angle)
                        twiddleImaginary[index * radix + outputDigit] = Math.sin(angle)
                    }
                }
            }
        }
    }
}
