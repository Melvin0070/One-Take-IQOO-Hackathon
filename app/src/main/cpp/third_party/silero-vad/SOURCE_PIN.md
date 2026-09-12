# Silero VAD model pin

The bundled model is the ggml conversion published for whisper.cpp.

- Model repository: https://huggingface.co/ggml-org/whisper-vad
- Repository revision: `9ffd54a1e1ee413ddf265af9913beaf518d1639b`
- File: `ggml-silero-v6.2.0.bin`
- File SHA-256: `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987`
- File size: 885098 bytes
- Silero source license revision: `be95df9152c0d7618fa1edfeb296fc3dae32376f`
- Upstream model license: MIT, retained in `LICENSE`.

The runtime uses the whisper.cpp VAD API already bundled by the caption
engine. The classifier consumes one 512-sample frame at 16 kHz per call.
