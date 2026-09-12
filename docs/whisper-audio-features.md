# Whisper audio features

`WhisperAudioFeatures.extract` converts one mono 16 kHz PCM waveform into the fixed `[80, 3000]` input used by the Qualcomm Whisper Tiny encoder.

The extractor first truncates audio to 30 seconds or right-pads it with zeros to 480,000 samples.
It then applies a 400-sample periodic Hann window every 160 samples with 200-sample reflect padding on both edges.
The final frame produced by the centered STFT is dropped, leaving 3,000 frames.

Mel filters use the Slaney mel scale, 0 to 8,000 Hz, 80 channels, and Slaney area normalization.
Each filter projects the one-sided 400-point power spectrum onto one mel channel.
The result is floored at `1e-10`, converted with `log10`, clamped to an 8-log-unit dynamic range below the waveform maximum, and normalized with `(value + 4) / 4`.

The returned `FloatArray` is channel-major, with index `mel * 3000 + frame`.
The implementation uses an in-place mixed-radix 400-point FFT factored as `5 * 5 * 2 * 2 * 2 * 2`, so it preserves the model's 400-point frequency bins without an O(N²) DFT.

The numerical tests use deterministic PCM waveforms and values generated from the Hugging Face `WhisperFeatureExtractor` and `audio_utils.spectrogram` reference implementation.
The primary references are [Whisper's feature extractor](https://github.com/huggingface/transformers/blob/main/src/transformers/models/whisper/feature_extraction_whisper.py) and [the shared spectrogram and mel filter implementation](https://github.com/huggingface/transformers/blob/main/src/transformers/audio_utils.py).

This class only prepares the encoder input.
Sample-rate conversion, microphone down-mixing, decoder token handling, timestamps, and NPU graph execution remain outside this API.
