# Architecture

## The spine loop

```
 laptop ──Office Kit clipboard──▶ script ──────────────────┐
                                                           ▼
 mic ──▶ audio ──▶ VAD ──▶ Recognizer ──▶ aligner ──▶ strip: "again, line N",
                        (CPU | replay)      │           coverage, Safe to wrap
 front camera ──▶ MP4 ──────────┐           ▼
                                │     ledger (append-only)
 NPU vision ──▶ face-in-frame   │           │
                                ▼           ▼
                       Wrap ──▶ edit list over raw files ──▶ instant playback
                                            └────▶ export (foreground) ──▶ gallery
```

1. Paste the script from the laptop over the Office Kit clipboard. One line per row.
2. A foreground service captures portrait 1080×1920 from the front camera with CameraX,
   strip directly under the lens.
3. VAD cuts utterances at pauses. Each goes to a `Recognizer`; an NPU vision model runs
   alongside for the face-in-frame signal.
4. The aligner opens and closes takes, applies scratches, appends verdicts to the ledger.
5. A take closing flubbed shows "again, line N" **at that line-end pause** — never
   mid-sentence. Otherwise the line turns covered and the strip advances. All covered →
   Safe to wrap, and Record becomes Wrap.
6. On stop, the ledger becomes the edit list and playback starts. No render step.
7. Export burns in captions in a media-processing foreground service, never while
   recording. Raw files and the ledger are always kept.

## The module graph

```
                         ┌──────────────┐
                         │   :engine    │  PURE JVM. No android.* — enforced by
                         │  the brain   │  kotlin("jvm"), so it is a compile error.
                         └──────┬───────┘
        ┌───────┬────────┬──────┼──────┬────────┬────────┐
        ▼       ▼        ▼      ▼      ▼        ▼        ▼
  :engine-   :eval     :asr   :npu  :capture :media   :link
  fixtures
        └───────┴────────┴──────┼──────┴────────┴────────┘
                                ▼
                              :app
```

The graph **is** the parallelization plan. Agents in different modules do not touch the
same files, so they do not conflict. Needing to edit another module's file means the seam
is in the wrong place — say so rather than reaching across.

| Module | Kind | Owns |
|---|---|---|
| `:engine` | JVM library | Interfaces, aligner, ledger, coverage fold, normalizer, edit-list rules, config |
| `:engine-fixtures` | JVM library | Corpus loader, golden ledgers, `FakeRecognizer`, `FakeVad`, `FakeVision` |
| `:eval` | JVM CLI | corpus → eval card, one command |
| `:asr` | Android library | sherpa-onnx **CPU**: Silero VAD, streaming transducer, keyword spotting |
| `:npu` | Android library | LiteRT + Qualcomm accelerator. The only module that links QNN |
| `:capture` | Android library | CameraX video, AudioRecord, foreground service, WAV, timebase anchor |
| `:media` | Android library | Media3 playback from the edit list, Transformer export, captions |
| `:link` | Android library | Multicam pairing, key exchange, encrypted transfer (Tier 2) |
| `:app` | Android app | Compose UI, all screens, debug panel, DI wiring, ATTRIBUTION |

**One NPU runtime.** `:asr` is sherpa-onnx on the **CPU**; `:npu` is LiteRT with the
Qualcomm accelerator. Nothing else touches a Qualcomm runtime. Verified by artifact
inspection: **zero `.so` filename collisions** between the two. The only filename that can
collide is `libonnxruntime.so`, and only if a *second* ONNX Runtime enters the build —
which fails at `dlopen`, not at build time. The static-link sherpa AAR removes that class
of failure entirely ([landmines L2](landmines.md)).

## The time model

**One master clock: the audio sample index.** 16 kHz mono PCM, one sample = 62.5 µs.

- Every ledger event, take boundary, word span and edit-list in/out point is a `Long`.
- Video enters through exactly **one** `VideoAnchor(sample, videoPtsNanos, sessionId)` per
  session, emitted by `:capture`.
- Wall-clock appears in log lines and nowhere else. **Never in the ledger.**

Three things follow for free: R1 collapses to one number in one place; R14's determinism
cannot be broken by a stray `System.currentTimeMillis()`; "close times are unique inside a
session" is true by construction rather than by hope.

It is also forced on you by two independent facts:

- sherpa's Kotlin binding **drops `start_time`**, so after every endpoint reset the
  recognizer's timestamps restart at ~0 and only your own sample counter recovers absolute
  time ([L5](landmines.md)).
- VAD gives you absolute sample indices for free — `SpeechSegment.start` is a linear,
  never-wrapping index. Which is also why **VAD cuts audio and ASR does not** ([L4](landmines.md)).

## A/V: Architecture B

**Settled by research on 2026-09-11.** Build B. Probe first, but expect B. Capture A's data
unconditionally so the fallback is a playback-path swap, not a rewrite at hour 20.

```
B (primary)   CameraX + withAudioEnabled() ─▶ MP4 with synced audio ─▶ clip it, that IS the cut
              AudioRecord(VOICE_RECOGNITION) ─▶ recognizer + a WAV kept anyway
              sync tolerance: HUNDREDS of ms, because cut boundaries land in pauses

A (fallback)  CameraX audio disabled ─▶ video-only MP4
              AudioRecord ─▶ recognizer AND the cut's audio
              sync tolerance: 33 ms, and CameraX gives you NO wall-clock anchor for MP4 t=0
```

Android's own camera docs settle it: when camera buffers go **directly to the encoder**,
the platform compensates for the audio/camera timebase difference automatically. Take the
buffers yourself and that compensation is gone.

**Neither is documented-safe.** The failure modes are asymmetric, and that is the whole
argument: **B fails loudly and detectably in code; A fails as a silent constant offset you
find on stage.** The probe and both holes are in [landmines L9](landmines.md).

## Threading

```
AudioRecord reader thread
  ├─ VAD           (synchronous — tiny)
  ├─ CommandSpotter(synchronous — tiny, NOT gated behind VAD)
  └─ bounded queue ─▶ one dedicated ASR thread

everything above ─▶ ONE channel ─▶ CoverageEngine.submit()  [single-threaded, ordered]
                                        │
                                        ▼
                                  Flow<LedgerEvent> ─▶ UI, persistence, edit list
```

`CoverageEngine.submit()` is single-threaded and totally ordered. Inputs are sorted by
sample index inside a bounded reorder window (the ASR thread runs behind the VAD thread;
the window absorbs that without letting a late `Final` reorder history).

Everything downstream falls out of that one rule: R14 is free, the eval harness is free,
replay-driven UI is free, and the brain has no locks and no races. Replay, evaluation and
UI development are literally the same call:

```kotlin
inputs.forEach(engine::submit)
```

`:asr` threading constraints — `OnlineRecognizer`, `Vad` and `KeywordSpotter` carry no
internal locks, and `numThreads` is per-engine — are in [landmines L15](landmines.md).

## Persistence

The ledger is append-only and written as events happen, tagged with session id. Derived
state — coverage, takes, the edit list — is **always recomputed** from the log. There is
nothing to corrupt, which is R13: kill the app mid-session and the project opens; at most
the current session's video is lost, every take pointing at lost video shows as **video
missing**, its line falls back to needed, and the cut skips it.

Everything lives in app-private storage on Android's file-based encryption until an
explicit export writes to the gallery (P2). Delete project removes raw files, ledger,
transcripts and app-made exports (P3).

## Configuration and flags

`RuntimeConfig` carries thresholds per line type, the hold limit, the line-end pause,
device offsets, command phrases, brand aliases, the recognizer choice, feature flags — and
a `configVersion`.

Red Light has no rebuilds, so new config versions arrive over Office Kit file transfer and
are imported into app storage like models, SHA-256 verified (R11). **Nothing tunable may be
hardcoded at a call site.**

Every Tier 1+ feature sits behind a flag in `FeatureFlags`, off by default. A flag turns on
when the feature's own check passes **and** the Tier 0 check still passes.
`RuntimeConfig.flagVectorChecksum()` is R25: both frozen demo loaners must show the same
checksum and config version before the demo. Ten seconds of checking, and it prevents the
worst avoidable failure of the weekend — two phones running the same APK with different
flags.

**`FeatureFlags` field order is frozen** — the checksum reads it positionally. Append only.

## Known critical gaps

Three codepaths have no test and no handling, and all three fail **silently**. They are
listed with their fixes in [`requirements.md`](requirements.md#known-gaps): storage fill
mid-session, recognizer silence, and playback before the MP4 is finalized.
