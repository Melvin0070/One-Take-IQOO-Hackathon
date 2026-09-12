# :asr — speech

sherpa-onnx, **CPU build**. Silero VAD, streaming zipformer transducer, keyword spotting.
Implements `:engine`'s `VoiceActivity`, `Recognizer` and `CommandSpotter`.

## Read `docs/agents/landmines.md` L1–L5 and L15 before your first line

This module has more silent-failure modes than the rest of the repo combined. The short
version, and every one of these is verified:

- **L1. sherpa below v1.13.8 returns EMPTY streaming output forever on SM8850.** No crash,
  no exception, no log line. Correct chunk counts, wrong encoder numbers. It will be blamed
  on the aligner for four hours. **Smoke-test ASR on the loaner in the first 30 minutes.**
- **L2. Use the static-link AAR, vendored in `libs/`.** 7.5 MiB smaller *and* it cannot
  collide with anything — it exports zero `Ort*` symbols. The convenient JitPack coordinate
  silently gives you the dynamic variant. See `libs/README.md`.
- **L3. `CircularBuffer::Push` EXITS THE PROCESS** if the 60 s VAD buffer fills, and the
  Kotlin binding cannot change the capacity. **`pop()` promptly.** Also: raise
  `maxSpeechDuration` — its 5 s default silently overrides your threshold and
  `minSilenceDuration` mid-session to force a split.
- **L4. ASR timestamps cannot cut audio.** 40 ms grid, emission peaks, BPE sub-words,
  undocumented lag. They are for *which line, roughly where*. **VAD indices cut audio.**
- **L5. The Kotlin binding drops `start_time`.** Count samples yourself and snapshot the
  counter at every `reset()`, or you cannot recover absolute stream time.
- **L15. `OnlineRecognizer`, `Vad` and `KeywordSpotter` have no internal locks.** One
  instance, one thread. `numThreads` is per-engine.

## Threading shape

```
AudioRecord reader thread
  ├─ VAD            synchronous, tiny
  ├─ CommandSpotter synchronous, tiny — NOT gated behind VAD segments
  └─ bounded queue ─▶ one dedicated ASR thread
```

Gating the spotter behind VAD adds the full `minSilenceDuration` (250 ms default) before it
sees any audio, which is the difference between "scratch that" feeling instant and feeling
broken.

**Never share an `OnlineStream`** between the recognizer and the spotter — different models,
different `createStream` factories. **Never rename the package**; JNI symbols are hardcoded
to `com.k2fsa.sherpa.onnx`.

## Models

| Purpose | Model |
|---|---|
| VAD | `silero_vad.onnx` (629 KB) or `silero_vad.int8.onnx` (208 KB). **16 kHz only** |
| ASR | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` — **int8 encoder (41 MB)** + fp32 decoder + int8 joiner |
| Commands | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (17.6 MB) |

**Prefer int8 encoders** — only int8 is verified end to end under onnxruntime 1.28.x.

The ASR model is **LibriSpeech-only** — read audiobook speech. Expect degradation on
spontaneous and accented delivery. That is a live risk for Indian-accented English and it
must show up in the held-out numbers. Measure it; do not assume it.

Keyword files are BPE tokens, space-separated, with `:<boost>` and `#<threshold>` and **no
space after the colon or hash**: `▁HE LL O ▁WORLD :1.5 #0.35`. Generate them on a laptop
with `sherpa-onnx-cli text2token` and check the file in — it is not an on-device step.

**Undocumented and useful:** `createStream(keywords)` takes runtime keywords with `/` as the
separator, *appended to* the file's. The command phrase list becomes tunable without a
rebuild, which is exactly what config-only tuning windows need.

## Measure and report

**Streaming zipformer RTF on modern ARM64 is not published anywhere.** Desktop x86 is 0.038
(int8, 1 thread). Budget thirty minutes to measure it on-device in hour one — it is the one
number the whole UX depends on.

## Your gap to close

A dead recognizer is currently **completely silent**: the model fails to load, VAD says
speech, and no final ever arrives. Ship the "not hearing you" chip after 4 s.
