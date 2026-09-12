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

The numerical tests use deterministic PCM waveforms and representative values generated from the Hugging Face `WhisperFeatureExtractor` and `audio_utils.spectrogram` reference implementation.
The [Qualcomm AI Hub Whisper v0.61.0 requirements](https://github.com/qualcomm/ai-hub-models/blob/5975a79b55b40f5cbc61f3ac5e52abe47d9d8bd5/src/qai_hub_models/models/_shared/hf_whisper/requirements.txt) pin `transformers==4.56.2`.
The primary references are [Whisper's feature extractor at the pinned source revision](https://github.com/huggingface/transformers/blob/cd74917ffc3e8f84e4a886052c5ab32b7ac623cc/src/transformers/models/whisper/feature_extraction_whisper.py) and [the shared spectrogram and mel filter implementation at that revision](https://github.com/huggingface/transformers/blob/cd74917ffc3e8f84e4a886052c5ab32b7ac623cc/src/transformers/audio_utils.py).
An independent NumPy run of those pinned operations produced the short waveform digest `70f2037b3776dcd1d7b49b1f49e0d1291328b5594a4de4152f31bfac8ba007a4` and full 30-second waveform digest `c60735c8cf4c095d3538624bd6d3197e3a67288c8826258c339bedfc14e22dd8`.
The Kotlin tests assert representative golden values with a `2e-5` tolerance because different math libraries can round individual FFT operations differently.

This class only prepares the encoder input.
Sample-rate conversion, microphone down-mixing, decoder token handling, timestamps, and NPU graph execution remain outside this API.
