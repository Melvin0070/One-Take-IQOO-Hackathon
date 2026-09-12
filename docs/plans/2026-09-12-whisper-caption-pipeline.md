# Whisper caption pipeline

The iQOO 15 already executes the pinned Qualcomm Whisper Tiny encoder and decoder on HTP.
The next change connects actual audio, token generation and model timestamps before production activation.

## Independent components

- Audio features: pure Kotlin 16 kHz PCM preprocessing with numerical reference checks.
- Token decoder: vocabulary parsing, language/task tokens, timestamp constraints, greedy sampling and bounded cancellation.
- Model installation: pinned artifact sizes/hashes, safe extraction and atomic installation.

Each component is developed on a separate branch and PR based on the QNN runtime.
The integration branch combines the reviewed components, owns Android graph state, and tests recorded speech on the device.

## Integration acceptance

The opt-in test must produce recognizable text from the existing synthetic speech recording with nonempty, ordered, bounded segment times.
It must execute encoder and decoder through strict NPU engine sessions.
Test cancellation, repeated utterances and resource release.
Preserve the existing CPU caption path until multilingual quality, word timing, overlapping windows, long recordings and recorder persistence/export are established.
Segment timestamps are not word alignment.
Do not close issue #3 based on graph or single-clip success.
