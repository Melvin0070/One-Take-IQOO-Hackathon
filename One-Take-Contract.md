# One-Take Engine Contract

Specified by `/plan-eng-review` on 2026-09-11, against `One-Take-Design-v3.md` (v3.1).
This is §10's "contract", written as prose and tables per §9 agenda item 5.
It is the source of truth for module boundaries, interfaces, the time model and the
state machine. Hand this to an agent and it can build `:engine` without reading the
design doc.

**Status of Open Question 20:** CLOSED. The team confirmed by phone with the organizers
on Sep 11 that pre-event code is allowed. §15's "measurement and data only; no app code"
and Appendix A's "No app code before Sat 11:00" are superseded. Get one line of that
confirmation in writing so nobody re-litigates it at 02:00.

---

## 1. Module graph

Nine modules. The graph is the parallelization plan: agents in different modules do not
touch the same files, so they do not conflict.

```
                         ┌──────────────┐
                         │   :engine    │  PURE JVM. Must not compile against android.*
                         │  the brain   │
                         └──────┬───────┘
        ┌───────┬────────┬──────┼──────┬────────┬────────┐
        ▼       ▼        ▼      ▼      ▼        ▼        ▼
   :fixtures  :eval    :asr   :npu  :capture :media   :link
        └───────┴────────┴──────┼──────┴────────┴────────┘
                                ▼
                              :app
```

| Module | Kind | Owns | Depends on |
|---|---|---|---|
| `:engine` | JVM library | Interfaces, aligner, ledger, coverage state machine, normalizer, edit-list rules, config types | nothing |
| `:engine-fixtures` | JVM library | Corpus loader, golden ledgers, `FakeRecognizer`, `FakeVad` | `:engine` |
| `:eval` | JVM CLI | corpus → eval card (one command, §6.2) | `:engine`, `:engine-fixtures` |
| `:asr` | Android library | sherpa-onnx **CPU** build: Silero VAD, streaming transducer, keyword spotting | `:engine` |
| `:npu` | Android library | LiteRT + Qualcomm accelerator: Face-Det-Lite, FaceMap-3DMM, HRNet-Pose, MiniLM, Qwen3 | `:engine` |
| `:capture` | Android library | CameraX video, AudioRecord, foreground service, WAV writer, timebase anchor | `:engine` |
| `:media` | Android library | Media3 playback from edit list, Transformer export, caption rendering | `:engine` |
| `:link` | Android library | Multicam pairing, key exchange, authenticated control, encrypted transfer (Tier 2) | `:engine` |
| `:app` | Android app | Compose UI, all screens, debug panel, DI wiring, ATTRIBUTION | all |

**Hard rule:** `:engine` has no Android dependency and no `import android.`. Enforce it by
using `kotlin("jvm")` rather than the Android library plugin, so the compiler refuses.

**Gradle, first commit:**
```
android.defaultConfig.ndk.abiFilters += "arm64-v8a"   // roughly halves the APK
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

---

## 2. Runtime rule: one NPU runtime — verified 2026-09-11

`:asr` uses the **sherpa-onnx CPU build**; `:npu` uses **LiteRT with the Qualcomm
accelerator** and is the only module that links QNN. Nothing else touches a Qualcomm runtime.
**Confirmed by artifact inspection: zero `.so` filename collisions between the two.**

| Side | Files it actually ships (arm64-v8a) |
|---|---|
| sherpa-onnx `sherpa-onnx-1.13.8.aar` | `libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libsherpa-onnx-jni.so` |
| LiteRT `litert:2.2.0` + `litert-api` | `libLiteRt.so`, `libLiteRtClGlAccelerator.so`, `liblitert_jni.so` |
| LiteRT NPU runtime zip, `qualcomm_runtime_v81` | `libLiteRtDispatch_Qualcomm.so`, `libLiteRtCompilerPlugin_Qualcomm.so`, `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV81Skel.so`, `libQnnHtpV81Stub.so`, `libQnnHtpPrepare.so`, `libQnnIr.so`, `libQnnSaver.so` |

**Corrected 2026-09-11 after artifact inspection.** An earlier draft of this section warned
that `com.qualcomm.qti:onnxruntime-android-qnn` would collide with LiteRT's QAIRT libraries.
**That was wrong.** That artifact ships exactly one file, `libonnxruntime_providers_qnn.so`,
and no QAIRT libraries at all. The real collision risk is different and narrower:

**The only filename that can collide is `libonnxruntime.so`**, and only if a *second* ONNX
Runtime enters the build — `com.microsoft.onnxruntime:onnxruntime-android(-qnn)` alongside
sherpa's default AAR. It is a nasty one: sherpa's JNI lib hard-requires symbol version
`VERS_1.28.2`, so a `pickFirst` that selects the other build **fails at `dlopen`, not at
build time**. This is documented in sherpa issues #3261, #2538, #601 and #1771 — all real
reports, all resolved by rebuilding sherpa rather than by packaging tricks.

### Use the static-link AAR. It is both safer and SMALLER.

sherpa-onnx publishes two Android AARs. This matters more than it looks:

| Variant | arm64-v8a contents | Total |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` (default) | `libonnxruntime.so` 21.2 MiB + `libsherpa-onnx-c-api.so` 4.3 + `libsherpa-onnx-cxx-api.so` 0.4 + `libsherpa-onnx-jni.so` 4.6 | **30.5 MiB** |
| `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | `libsherpa-onnx-jni.so` only, 23.0 MiB | **23.0 MiB** |

The static build exports and imports **zero `Ort*` symbols** and has no `libonnxruntime.so`
dependency, so it cannot collide with anything, now or later. It is also 7.5 MiB smaller per
ABI because it drops the unused c-api/cxx-api libs and the dead ORT code — which matters when
you sideload every build to three loaners over Office Kit.

**The catch: you cannot get it from Gradle.** sherpa is not on Maven Central; it publishes
via JitPack as `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8` (with
`maven { url 'https://jitpack.io' }`), and **`jitpack.yml` wraps the DYNAMIC variant**. Taking
the convenient coordinate silently gives you the collision-prone, larger build. Vendor the
static release asset into `libs/` instead. Two minutes, and it removes a whole failure class.

Also verified: **no `libc++_shared.so` collision.** sherpa's libs statically link libc++
(DT_NEEDED is only libandroid, liblog, libm, libdl, libc).

### 🔴 STOP: sherpa-onnx must be v1.13.8 or newer, or nothing works on SM8850

**sherpa-onnx v1.13.7 and earlier produce EMPTY streaming-ASR output forever on Snapdragon
8 Elite Gen 5.** No crash. No exception. No log line. Correct chunk counts, correct decode
call counts, wrong encoder numbers. Root cause is onnxruntime 1.27.x miscomputing the
zipformer2 encoder on that SoC (sherpa-onnx issue #3845).

- v1.13.7 pins `onnxruntime_version=1.27.1` ← **broken on your loaner**
- v1.13.8 pins `onnxruntime_version=1.28.2` ← **released 2026-09-10, the day before you build**

**Swapping only `libonnxruntime.so` does not work.** `libsherpa-onnx-jni.so` requires the
versioned symbol `OrtGetApiBase@@VERS_1.27.0`. Take the whole v1.13.8 AAR.

**Prefer int8 encoders.** The reporter could not confirm the fp32 encoder is fixed under
1.28.x in a real streaming pipeline; only int8 is verified end to end. Smoke-test ASR on the
loaner in the first 30 minutes of Saturday — a silent empty-output failure is exactly the
kind of thing that gets blamed on your own aligner for four hours.

**Model:** `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, **int8 encoder (41 MB) +
fp32 decoder + int8 joiner**. Best speed/size on a phone. Note it is LibriSpeech-only —
read audiobook speech — so expect degradation on spontaneous and accented delivery. That is
a live risk for Indian-accented English reads and it should show up in the R2/R5 held-out
numbers. Measure it; do not assume.

**RTF on a modern ARM64 phone is NOT PUBLISHED for streaming zipformer.** Desktop x86 is
0.038 (int8, 1 thread). Budget 30 minutes to measure it on-device in hour one; it is the one
number your whole UX depends on and nobody has published it.

### 🔴 CORRECTION: ASR timestamps CANNOT be used to splice audio

An earlier draft of this contract assumed "a streaming zipformer transducer gives word
timings good enough to splice audio at word boundaries." **That assumption is wrong**, for
five independent reasons, and it invalidates two rules as originally written.

`OnlineRecognizerResult` gives `tokens: Array<String>` and `timestamps: FloatArray`. What
those timestamps actually are:

1. **A 40 ms grid.** `timestamps[i] = 0.04 * encoder_frame_index` (10 ms frame shift ×
   subsampling factor 4). Nothing lands between multiples of 40 ms.
2. **Emission peaks, not boundaries.** The value is the frame at which the transducer
   *emitted* a symbol. There is no start, no end, no duration. Asked this exact question, the
   maintainer's answer was to use a forced aligner (Montreal Forced Aligner) instead.
3. **BPE sub-word tokens, not words.** `"YELLOW"` comes back as `" YE","LL","OW"` with three
   timestamps. You can derive a word *start* from the leading-space marker, but **word end
   does not exist** — the best available is "next word's start", which includes the gap.
4. **Trained with no delay penalty.** icefall exposes `--delay-penalty` to encourage earlier
   emission; the zipformer recipe that produced every sherpa streaming model has no such
   option. Emission delay is entirely unconstrained.
5. **Forced alignment is an open request, declined in its CTC form.** Nothing shipped.

**The magnitude of the lag is NOT DOCUMENTED anywhere.** Do not design around a number
nobody published.

**What this changes:**

| Rule | As written | Corrected |
|---|---|---|
| **R3, one-breath split** | "split at a word boundary using word timestamps" | **R3 splits COVERAGE, not audio.** An utterance spanning lines N and N+1 marks both covered; the edit list emits **one** segment for the whole utterance. No audio cut, so no word boundary is needed. This is strictly better: it removes the requirement AND avoids a splice in the middle of natural speech |
| **#15, filler splicing** | "spliced out at word boundaries" | **Splice only on VAD silences.** You can remove a filler that sits between pauses ("um" between sentences); you **cannot** remove one mid-sentence without an audible clip or bleed. State the reduced capability rather than shipping a glitch |
| **Take boundaries** | "splices at voice-detector boundaries" | ✅ **Already correct.** §10 got this right. VAD boundaries are real silences and are the only safe cut points |
| **R4, miscue reasons** | word-level alignment after normalization | ✅ **Unaffected.** That is text-to-text alignment, not audio, and it works fine |

**General rule for the whole build: ASR timestamps are for progress tracking (which line, roughly
where). VAD sample indices are for cutting audio. Never cross the two.**

### The Kotlin binding drops absolute time — you must count samples yourself

C++ computes `start_time` (seconds since stream start) and `segment`, but the JNI constructor
signature is `(Ljava/lang/String;[Ljava/lang/String;[F[F)V` — text, tokens, timestamps,
ysProbs. **`start_time` and `segment` are dropped on the floor.** After every endpoint reset,
timestamps restart at ~0 and Kotlin cannot recover absolute stream time.

This makes §3's audio-sample-index master clock **mandatory rather than merely elegant**:
count every sample you pass to `acceptWaveform`, snapshot the counter at each `reset()`, and
add `snapshot / 16000.0` to every timestamp.

Conveniently, **VAD already gives you absolute sample indices for free.**
`SpeechSegment.start` comes from `CircularBuffer::Tail()`, documented as "linear index;
always increasing; never wraps around", zeroed only by `reset()`. Seconds = `start / 16000.0`.
There is no `end` field — derive it as `start + samples.size`.

### VAD behaviours that will surprise you

```kotlin
data class SileroVadModelConfig(
    var model: String = "", var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,   // seconds
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,               // Silero v5 @16k
    var maxSpeechDuration: Float = 5.0F,
)
```

- **Speech start is backdated** by roughly 314 ms of pre-roll
  (`start = max(tail - 2*windowSize - minSpeechDurationSamples, head)`). Good — no clipped
  word onsets — but `start` is not the first speech frame.
- **Speech end is trimmed** to `tail - minSilenceDurationSamples`. Trailing silence is cut.
- **`maxSpeechDuration` (5 s) is a forced-split hack.** Past it the code silently overrides
  your config to `minSilenceDuration = 0.1` and `threshold = 0.90` to force a break. **Raise
  it** — your creators read long unbroken lines, and a silent config override mid-session is
  a debugging nightmare at 03:00.
- **`CircularBuffer::Push` EXITS THE PROCESS if the 60 s buffer fills**, and the Kotlin
  binding gives you no way to change the capacity. **Always `pop()` promptly.** This is a
  hard crash source, not an exception.

Model: `silero_vad.onnx` (629 KB) or `silero_vad.int8.onnx` (208 KB). 16 kHz only.

### Keyword spotting for "scratch that"

Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (17.6 MB; int8 encoder 4.6 MB).
Keyword file format is BPE tokens, space-separated, then optional `:<boost>` and `#<threshold>`
with **no space after the colon or hash**:

```
▁HE LL O ▁WORLD :1.5 #0.35
```

Generate it on a laptop with `sherpa-onnx-cli text2token --tokens-type bpe --bpe-model ...`.
It is not an on-device step, so **do it tonight** and check the file in.

**Undocumented and very useful:** `createStream(keywords)` accepts runtime keywords with `/`
as the line separator, appended to (not replacing) the ones from `keywordsFile`. That means
the command phrase list in the runtime config can be tuned without a rebuild, which is
exactly what Red Light needs.

**Do not gate KWS behind VAD segments** — it adds the full `minSilenceDuration` finalisation
delay (250 ms default) before the spotter sees any audio.

### Threading

`OnlineStream` is internally mutex-guarded on every public method. `OnlineRecognizer`, `Vad`
and `KeywordSpotter` carry **no such locks** — one instance, one thread. `numThreads` defaults
to 1 and is the ORT intra-op count *per engine*, so three engines at 2 threads is six ORT
threads competing on a phone.

Shape: one `AudioRecord` reader thread fans the same buffer to three consumers. VAD and KWS
run synchronously on that thread (both are tiny). ASR chunks go onto a bounded queue drained
by one dedicated thread. **Never share an `OnlineStream` between the recognizer and the
spotter** — different models, different `createStream` factories.

**Do not rename the package.** JNI symbols are hardcoded to `com.k2fsa.sherpa.onnx`.

### LiteRT NPU: the exact setup

```kotlin
implementation("com.google.ai.edge.litert:litert:2.2.0")   // the ONLY Maven artifact
```

There is no Qualcomm Maven artifact. The QNN libraries come from the **GitHub release zip of
the matching tag**: `litert_npu_runtime_libraries_jit.zip` from release **v2.2.0**.

> **PIN THE VERSIONS TOGETHER. Never `litert:+`.** A mismatch between the Maven AAR and the
> runtime `.so` files is the single biggest failure mode, and it presents as
> `dlopen failed: cannot locate symbol "LiteRtQualcommOptionsGet"` followed by the app
> running happily on CPU while logging "NPU initialised successfully". This is LiteRT issue
> #7891, on real Snapdragon hardware.

**You need `qualcomm_runtime_v81` for SM8850.** The runtime modules are per-Hexagon-version
and Google's own blog example only includes `v79`:

| Module | Device group | Chip |
|---|---|---|
| `qualcomm_runtime_v73` | `Qualcomm_SM8550` | 8 Gen 2 |
| `qualcomm_runtime_v75` | `Qualcomm_SM8650` | 8 Gen 3 |
| `qualcomm_runtime_v79` | `Qualcomm_SM8750` | 8 Elite |
| **`qualcomm_runtime_v81`** | **`Qualcomm_SM8850`** | **8 Elite Gen 5 — the iQOO 15** |

**Bypass Play dynamic-feature delivery.** The runtime libs ship as
`com.android.dynamic-feature` modules with `dist:install-time` device-group conditions — a
Play delivery mechanism a sideloaded debug APK is not guaranteed to carry, and you sideload
every build over Office Kit. `BuiltinNpuAcceleratorProvider.getLibraryDir()` returns
`context.applicationInfo.nativeLibraryDir`, which is exactly where
`app/src/main/jniLibs/arm64-v8a/` lands. **Copy the nine `.so` files straight into `jniLibs`**
(plus the rehearsal phone's `V75`/`V73` Skel+Stub) and skip AI Packs entirely. Keep
`useLegacyPackaging = true`, `abiFilters "arm64-v8a"`, `minSdk 31`.

### R9: proving it ran on the NPU — every obvious route is a trap

**`CompiledModel.Options(Accelerator.NPU)` DOES NOT THROW when the NPU is unavailable.** From
LiteRT's own Kotlin source:

```kotlin
val accelerators =
  if (options.accelerators.size == 1 && options.accelerators.first() == Accelerator.NPU) {
    // If NPU is the only accelerator, CPU is added to support partially compiled models.
    setOf(Accelerator.NPU, Accelerator.CPU)
  } else { options.accelerators }
```

Ask for NPU and you silently get NPU-or-CPU. **This is the exact mechanism that made earlier
teams in this series ship on CPU while believing otherwise.**

| Route | Verdict |
|---|---|
| `Environment.getAvailableAccelerators()` | ❌ **It lies.** Returned `[NPU, GPU, CPU]` on a device where the Qualcomm plugin had failed to `dlopen`. It reports registration, not vendor-plugin health |
| `NpuCompatibilityChecker.Qualcomm.isDeviceSupported()` | ❌ It is a `Build.SOC_MANUFACTURER` string comparison |
| Any getter on `CompiledModel` | ❌ None exists in Kotlin. The JNI exports zero accelerator/profiler/metrics functions; `IsNonCpuFullyAccelerated()` is C++ only |
| **Force the set to reach native as NPU-only and let `create` throw** | ✅ **The only route** |

```kotlin
// size == 2, so the auto-CPU-append does not fire.
// Accelerator.NONE maps to a JNI no-op, so native receives {NPU} alone.
// create() then throws LiteRtException when delegation is not total.
CompiledModel.Options(setOf(Accelerator.NPU, Accelerator.NONE))
```

A LiteRT maintainer confirms the contract for GPU and says "the same for NPU"; an in-repo
test pins the failure to `kLiteRtStatusErrorCompilation`. **The Kotlin route to it (the
`NONE` trick) is NOT DOCUMENTED and must be confirmed on-device tonight.** Note
`LiteRtException.status` is private with no getter, so Kotlin sees only the message string.

**R9's implementation, then:** log `create` success, measured `model.run()` time, and
`Build.SOC_MODEL` per session. Corroborate with `adb logcat -s litert:V tflite:V` — the
string `TfLiteXNNPackDelegate` in the log means you are on CPU. Set
`QualcommOptions(logLevel = VERBOSE)` while debugging.

**Make the failure loud at app start.** A mandatory startup self-test that builds the model
NPU-only and lets the exception reach a red banner. Fail visibly on your laptop tonight
rather than silently at 0.195 ms → 146 ms on stage. Any deliberate CPU fallback uses a
*different* `CompiledModel` instance and a *visibly different label* — never a fallback that
reuses the "NPU" label.

### Tonight's rehearsal phone: works for vision, not for Whisper

The JIT path ships a **portable `.tflite`** compiled to NPU bytecode at first load, so the
same artifact runs on any supported Snapdragon. Include both `v81` (target) and the rehearsal
phone's module. Face-Det-Lite timings across chips: 8 Gen 1 0.5 ms, 8 Gen 3 0.281 ms, 8 Elite
0.239 ms, **8 Elite Gen 5 0.195 ms** — same order of magnitude, so a green rehearsal
generalises.

**It does not generalise for Whisper or Qwen3, and this breaks an assumption in Appendix B.**

| Model | Format | Portable? | Runs through LiteRT? |
|---|---|---|---|
| Face-Det-Lite (Lightweight-Face-Detection) | TFLITE w8a8, **Universal** | ✅ | ✅ 0.195 ms, BSD-3 |
| FaceMap-3DMM (Facial-Landmark-Detection) | TFLITE w8a8, Universal | ✅ | ✅ 0.117 ms, BSD-3 |
| HRNet-Pose | TFLITE w8a8, Universal | ✅ | ✅ 0.516 ms, MIT |
| MiniLM-v2 | TFLITE float, Universal | ✅ | ✅ 0.482 ms, Apache-2.0 |
| **Whisper-Small-Quantized** | **QNN context binary, PER-CHIPSET** | ❌ | ❌ **not LiteRT** |
| **Whisper-Large-V3-Turbo** | **QNN context binary, PER-CHIPSET** | ❌ | ❌ **not LiteRT** |
| **Qwen3-4B-Instruct** | **Genie / GENIEX, per-chipset** | ❌ | ❌ **separate Genie runtime** |

The Whisper and Qwen3 downloads are literally named
`...-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip` and **will not load on tonight's
phone at all**. They are a wholly separate integration from `CompiledModel`. This reinforces
§2's recognizer decision: the streaming transducer on CPU is the spine, and NPU Whisper was
never the cheap upside it looked like.

### Two model gotchas that cost a morning each

- **Face-Det-Lite input is `[1, 480, 640, 1]` uint8 GRAYSCALE, not RGB.** The AI Hub page
  says "480x640" and the export's `metadata.json` says `color_format: "grayscale"`. Model is
  ~967 KB unzipped, downloadable unauthenticated from the public S3 bucket (no AI Hub
  account needed — that is only for hosted compile/profile jobs).
- **MiniLM-v2 exports the transformer only.** 384-dim output, 128-token input. You implement
  WordPiece tokenisation and mean-pooling plus L2 normalisation yourself; without the
  normalisation the vectors are not cosine-comparable. Budget that into #20 talking points.
  Note its `w8a8` variant is *slower* than float here (0.945 vs 0.482 ms) — use float.

### Thermal: less dangerous than assumed

**Nothing in Android or the QNN runtime documents refusing NPU work at
`THERMAL_STATUS_SEVERE`.** The QNN HTP backend docs contain zero occurrences of "thermal",
"throttl" or "temperature". Android's NPU Manager *can* request a model unload on a thermal
spike, but it is **Android 17+** and the iQOO 15 runs Android 16.

So clocks drop and latency rises; nothing hard-refuses. Practical consequences:
- Use `QualcommOptions(htpPerformanceMode = SUSTAINED_HIGH_PERFORMANCE)`, not `BURST`, for a
  long recording session.
- Register a thermal listener so the on-stage overlay can say "SEVERE, NPU still running,
  0.4 ms" instead of going quiet. That is a better moment than hiding it.
- R9 should report *per-inference time and a count*, never a latency SLA.

## 3. Time model

**One master clock: the audio sample index.** 16 kHz mono PCM, so one sample is 62.5 µs.

- Every ledger event, take boundary, word span and edit-list in/out point is a `Long`
  sample index.
- Video enters through exactly **one** `VideoAnchor(sampleIndex, videoPtsNanos)` per
  session, emitted by `:capture`.
- Wall-clock time appears in log lines and nowhere else. Never in the ledger.

Consequences: R1 collapses to one number in one place; R14 determinism cannot be broken by
a stray `System.currentTimeMillis()`; §10's "close times are unique inside a session" is
true by construction rather than by hope.

### R1: SETTLED by research, 2026-09-11

**Architecture B is primary. Probe decides at runtime. A's data is captured unconditionally
as the fallback.**

Android's own `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` documentation is decisive:

> "When buffers from a REALTIME device are passed **directly to a video encoder from the
> camera**, automatic compensation is done to account for differing timebases of the audio
> and camera subsystems. **If the application is receiving buffers and then later sending
> them to a video encoder** or other application where they are compared with audio
> subsystem timestamps, **this compensation is not present.**"

Let the camera mux its own audio and the platform compensates. Take the buffers yourself
and you own the problem.

```
ARCHITECTURE B  (primary)
  CameraX Recorder + withAudioEnabled() ──▶ MP4 with its own synced audio track
  AudioRecord (VOICE_RECOGNITION)       ──▶ recognizer, AND the WAV (kept regardless)
  the cut = clipped segments of that MP4
  sync tolerance: map recognizer sample index -> MP4 time to within a few HUNDRED ms,
                  because every cut boundary lands in a speech pause
  deletes: R1's 33ms budget, the clap rig, per-device offset config, a Red-block task

ARCHITECTURE A  (fallback, only if the probe fails)
  CameraX Recorder, audio DISABLED ──▶ MP4 video only
  AudioRecord ──▶ recognizer AND the cut's audio
  playback: MergingMediaSource(adjustPeriodTimeOffsets = true, [video, wav])
```

**Neither is documented-safe. Know which hole you are standing over.**

- **B's hole:** CameraX's `Recorder` captures with `MediaRecorder.AudioSource.CAMCORDER`
  (verified in AndroidX source, not a documented contract), and CAMCORDER is
  privacy-sensitive by default, which per `AudioRecord.Builder` means *"any concurrent
  capture is not permitted."* But every rule that would silence your recognizer is written
  about **applications** — CDD §5.4.5 included. Same-app, two-client capture is outside the
  compatibility contract entirely, so an OEM can do anything here and still pass CTS.
  `AudioRecordingConfiguration`'s client-scoped accessors (`getClientAudioSource`,
  `isClientSilenced`) suggest same-UID clients are not what the rule targets, but that is
  inference.
- **A's hole:** CameraX exposes **no** wall-clock anchor for MP4 t=0. `RecordingStats` gives
  `getRecordedDurationNanos()` — a duration, not a clock — plus bytes and audio stats, and
  `VideoRecordEvent` gives nothing. Building a 33 ms lip sync requires binding a parallel
  `ImageAnalysis` and assuming its first frame maps to MP4 t=0, which the docs never state.

**Decision rule: B's failure is loud, instant and detectable in code; A's is a silent
constant offset discovered on stage.** Build B.

### The probe — 30 minutes, before any feature work

Start CameraX with `withAudioEnabled()`, start a `VOICE_RECOGNITION` `AudioRecord`, run
10 seconds, assert all three:

1. CameraX: `videoRecordEvent.recordingStats.audioStats.audioState == AUDIO_STATE_ACTIVE`
   and `getAudioAmplitude() > 0`
2. `AudioRecord`: buffers are **not all zero** (check directly)
3. `AudioRecordingConfiguration.isClientSilenced() == false`

**Run it in both start orders.** CDD [C-1-4] makes "most recently started wins" the tiebreak
for equal-priority capturers; if the OEM reuses that path for same-app clients, order
matters.

**Do not rely on the callback alone.** `registerAudioRecordingCallback()` must be called
*before* `startRecording()`, and the docs say it fires "only when the app is receiving audio
and a change occurs" — so silence from frame zero may never fire it. Check buffers for zeros
directly, and `getAudioAmplitude()` on the CameraX side.

**Design the fallback into the seam, not as a rewrite.** Run the `AudioRecord` and write the
WAV **unconditionally**, even under B where you do not need it. Then the fallback is a
playback-path swap, not a re-architecture at hour 20. If B holds, the WAV costs a few MB. If
B breaks, A's data is already on disk.

### If you land in A, the clock pairing is documented

Read `SENSOR_INFO_TIMESTAMP_SOURCE` via `Camera2CameraInfo.from(cameraInfo)
.getCameraCharacteristic(...)` — or `Camera2Interop.getCameraCharacteristics(cameraInfo)` on
CameraX 1.7.0-alpha03+, where `Camera2CameraInfo` is deprecated. Then pair:

| Sensor timestamp source | Use this AudioTimestamp timebase | Equivalent clock |
|---|---|---|
| `UNKNOWN` (0) | `TIMEBASE_MONOTONIC` | `System.nanoTime()` |
| `REALTIME` (1) | `TIMEBASE_BOOTTIME` | `SystemClock.elapsedRealtimeNanos()` |

Paired this way the two clocks compare directly with no offset measurement. The MP4 anchor
gap remains: bind `ImageAnalysis` alongside `VideoCapture` and take the first frame timestamp
at `EVENT_TYPE_START`. Verify against a real clap. Do not trust it unmeasured.

## 4. The engine surface

Everything Android must implement, and the single entry point.

```kotlin
// ---------- inputs the platform pushes in ----------
data class AudioFrame(val pcm: ShortArray, val startSample: Long)
data class Word(val text: String, val startSample: Long, val endSample: Long, val conf: Float)

sealed interface RecognizerResult {
  data class Partial(val text: String, val startSample: Long) : RecognizerResult
  data class Final(val words: List<Word>, val startSample: Long, val endSample: Long) : RecognizerResult
}

sealed interface VadEvent {
  data class SpeechStart(val sample: Long) : VadEvent
  data class SpeechEnd(val sample: Long) : VadEvent
}

data class VisionFrame(
  val sample: Long, val faceInFrame: Boolean, val bbox: FloatArray?,
  val inferenceMicros: Long, val processor: String   // "NPU" | "GPU" | "CPU"
)

data class VideoAnchor(val sample: Long, val videoPtsNanos: Long, val sessionId: String)

sealed interface UserAction {
  data object Advance : UserAction            // tap or remote
  data object Scratch : UserAction            // tap or remote
  data class Circle(val takeId: String) : UserAction
  data class Undo(val eventId: Long) : UserAction
  data object Stop : UserAction
}

// ---------- the interfaces the platform implements ----------
interface Recognizer {                        // :asr, and FakeRecognizer in :engine-fixtures
  fun start(cfg: RecognizerConfig)
  fun accept(frame: AudioFrame)
  fun close()
  val results: Flow<RecognizerResult>
}

interface VoiceActivity {                     // :asr (Silero), and FakeVad
  fun accept(frame: AudioFrame): VadEvent?
}

interface VisionSignal {                      // :npu
  val frames: Flow<VisionFrame>
}

// ---------- the single entry point ----------
sealed interface EngineInput   // AudioFrame | RecognizerResult | VadEvent
                               // | VisionFrame | VideoAnchor | UserAction

class CoverageEngine(config: RuntimeConfig, script: Script) {
  fun submit(input: EngineInput)              // SINGLE-THREADED, TOTALLY ORDERED
  val events: Flow<LedgerEvent>               // the only output
  fun snapshot(): CoverageState               // pure fold over the ledger
}
```

**The load-bearing rule: `submit()` is single-threaded and totally ordered.** One channel,
one consumer loop. Inputs are sorted by sample index within a bounded reorder window and
processed in order. This is what makes R14 free, replay free, the eval harness free, and
T3's replay-driven UI free. It also means the brain has no locks and no races.

Replay, the eval harness and the UI-development path are then literally the same call:
```kotlin
inputs.forEach(engine::submit)
```

---

## 5. Data model

```kotlin
data class Script(val lines: List<ScriptLine>)
data class ScriptLine(
  val id: LineId,           // stable across text edits (R18) and across sessions
  val text: String,
  val type: LineType,       // LINE | MUST_SAY | TALKING_POINT | CUTAWAY
  val version: Int          // bumps on text edit; takes record the version they were read against
)

data class Take(
  val id: TakeId, val lineId: LineId, val sessionId: String,
  val startSample: Long, val endSample: Long,
  val verdict: Verdict?, val scratched: Boolean, val circled: Boolean,
  val offFrame: Boolean, val videoMissing: Boolean, val lineVersion: Int
)

sealed interface Verdict {
  data class Clean(val accuracy: Float) : Verdict
  data class Flubbed(val reason: MiscueType, val detail: String) : Verdict
  data class MissingWords(val missing: List<String>) : Verdict
}

enum class MiscueType { OMISSION, INSERTION, MISPRONUNCIATION, REPETITION, SELF_CORRECTION }
```

**DRY:** `MiscueType` is the single source for §5.1's plain-word reasons, §7.3's
Reading-Progress miscue types, R4's per-verdict reason and #12's display. One enum, one
presentation map in `:app`. Not four lists.

**DRY:** one `Normalizer` in `:engine` with three thresholds (normal, must-say strict,
caption), not three implementations. R2, the §10 aligner rules and §7.3 currently describe
the same thing three times.

### Ledger events

Append-only, every event tagged with `sessionId` and `sample`:

`utterance-closed`, `take-opened`, `take-closed`, `verdict(take, result, reason)`,
`coverage-changed(line, state)`, `retake-needed(line)`,
`take-scratched(take, source: voice|tap|remote|auto)`, `take-circled(take)`,
`take-off-frame(take)`, `take-video-missing(take)`, `line-edited(line, version)`,
`line-deleted(line)`, `line-reordered(order)`, `wrap-ready`, `session-stopped`,
`playback-ready`, `export-progress`, `project-deleted`, `sync-offset(device, session)`,
`audio-route-changed(device)`.

Derived state is always recomputed from the log. A crash never corrupts it.

### Runtime config

Thresholds per line type, hold limit, line-end pause, device offsets, command phrases,
brand aliases, recognizer choice, feature flags — **plus `configVersion`**, which §7.3's
eval-card rule needs and the v3 list omits. Every Tier 1+ feature sits behind a flag, off
by default. New versions arrive over Office Kit and are imported into app storage like
models (R11).

**Bare-loop exception (resolves a real contradiction in §10):** R12's 13:00 bare loop needs
CPU recognition, which needs a model on the phone, but the import screen lands at 16:30.
Debug builds read the model from a fixed shared-storage path that Office Kit file transfer
drops into, with no hash check. The import screen and SHA-256 verification land at 16:30 to
satisfy P5/R11. Two lines, so nobody improvises it at 12:40.

---

## 6. Coverage state machine (§5.2, authoritative)

Lives in `:engine` as a pure fold. **T2 owns it. T3 renders `coverage-changed` events and
never computes coverage.** The v3 tracks table gives "coverage states" to T3 and "aligner"
to T2, which is one machine with two owners.

| From | Event | To |
|---|---|---|
| unread | a take for this line closes | pending |
| unread | speech matches a later line (skipped) | needed |
| pending | verdict clean (must-say: word-perfect) | covered |
| pending | verdict flubbed or missing words | needed |
| needed | a new take for this line closes | pending |
| covered | a new take of *that* line closes | covered; replaces the cut take only if clean |
| covered | its only clean take is scratched | needed |
| needed | the scratch is undone | covered |
| any | the line's text is edited | needed; id unchanged, takes kept and marked "read against an earlier version" (R18) |
| any | **the line is deleted** | takes marked orphaned and kept (Premise 12, non-destructive) — **new, Issue 9** |
| any | **lines are reordered** | ids unchanged; cut order re-derived from new positions — **new, Issue 9** |
| covered | its only clean take's video is lost | needed; take shows video missing (R13) |

**Wrap ready:** every line covered, must-say by strict verdict. A pending verdict **blocks**
wrap ready until it lands. The hold limit moves the strip, never the coverage state.

---

## 7. Aligner rules (deltas from §10 only)

§10's rules stand. These are the corrections and additions this review found.

1. **Joining.** Each utterance joins the open take, or opens a take for whichever line it
   matches best **across the whole script**, biased toward current / next / needed by a
   margin from config. Covered lines are candidates. *(§10, unchanged — but see R20 below,
   which gives it the acceptance test it was missing.)*

2. **Edit-list order — CORRECTION.** §10 step 6 reads *"the latest clean take of that line
   across all of the project's sessions, ordered by take-close time"*, which is ambiguous and
   whose natural reading scrambles the cut. Split into two clauses:
   - **Selection:** per line, the circled take if any, else the latest clean take by
     take-close time across all sessions.
   - **Order:** the cut is ordered by **script line order**. Never by take-close time.

   Without this, a pickup of line 1 recorded last plays last, and §5.2 step 11 puts a pickup
   in the demo.

3. **Must-say is never split.** R3's one-breath split at a word boundary, applied to a
   must-say line, fails the word-perfect rule on a clean read if the split lands one word
   off. That is Premise 10's worst failure, on the disclosure line, in front of a juror.
   Force the take to close at the must-say boundary instead.

4. **Must-say takes are never spliced.** #15 removes fillers from clean takes. For a
   must-say line that ships a legally-required disclosure the creator never said
   contiguously, which is exactly what brief mode claims to prevent.

5. **Filler splicing needs a silence guard.** Transducer token timestamps lag the acoustic
   event, so a splice can cut mid-word — an audible glitch inside The Vanish, which is the
   payoff beat. Splice only where there is **>150 ms of silence on both sides** of the
   filler; otherwise mark it and leave the audio alone.

6. **R9 wording — CORRECTION.** R9 currently promises the NPU inference count "is never
   zero — including in hot-phone mode." At `THERMAL_STATUS_SEVERE` the OS can refuse NPU
   work regardless of your duty cycle. Reword to: *the app never disables it; if the count is
   zero, the log names the thermal status that caused it.* Same evidence, survives being
   asked.

---

## 8. New requirements

| ID | Requirement | Acceptance test | Source |
|---|---|---|---|
| **R20** | A take of an already-covered line joins **that** line, not the current one | Session reads lines 1,2,3 then re-reads line 1 with a flub. Assert: line 1 has two takes; line 3 keeps its clean take and is never marked needed; the cut plays line 1's first (clean) take | §10 aligner rules describe this and call it the failure Premise 10 ranks worst, but v3 gives it no R-number and no test |
| **R21** | The cut is ordered by script line order, never by take-close time | Cover line 3, then record a pickup for line 1. Assert the cut plays line 1 then line 3 | §10 step 6 ambiguity |
| **R22** | Verdict latency is logged as three components, not one | Every take logs VAD-endpoint, decode-finalize and align times separately; the eval card reports all three | R6 cannot be tuned from a single number |
| **R23** | A mid-session audio route change is recorded | Connect the Bluetooth remote mid-session; assert an `audio-route-changed` event lands in the ledger | An unexplained accuracy cliff at 03:00 is unfixable without it |
| **R24** | Deleting or reordering script lines is non-destructive | Delete a covered line: its takes are kept and marked orphaned. Reorder lines: ids unchanged, cut order follows the new positions | R18 covers edits only |

---

## 9. Failure modes, per new codepath

| Codepath | Realistic failure | Test? | Handled? | User sees |
|---|---|---|---|---|
| `:capture` timebase anchor | Camera reports a clock the audio side didn't match; offset drifts | R1 | yes, clap fallback | Lip sync off. **Would be silent** without R1's spread test |
| `:capture` storage | 1080p ≈ 100 MB/min fills the disk mid-session | **GAP** | **GAP** | **CRITICAL GAP — silent truncation.** Add threshold stop + truncated marker |
| `:capture` audio route | BT remote connects, route moves to its mic | R23 (new) | new | Accuracy cliff, previously unexplained |
| `:capture` camera lost | OriginOS or another app takes the camera | R13 machinery | reuse | Takes become `video missing`, lines go needed |
| `:asr` recognizer silent | Model failed to load; VAD says speech, no finals | **GAP** | **GAP** | **CRITICAL GAP — silent.** Add "not hearing you" chip after 4 s |
| `:asr` KWS false fire | A script line containing "cut" scratches a take | R8 | yes | Take vanishes wrongly. Covered |
| `:npu` silent CPU fallback | LiteRT falls back; the NPU story is a prop | R9 | yes | Overlay shows processor. Covered |
| `:engine` aligner join | Re-read charged to the wrong line | **R20 (new)** | new | False flag on a clean read. Premise 10's worst case |
| `:engine` edit list | Cut ordered by close time | **R21 (new)** | new | Scrambled cut on stage |
| `:engine` must-say split | Split one word off fails word-perfect | Issue 8 | new rule | False flag on the disclosure line |
| `:media` export | `mediaProcessing` FGS killed by OriginOS | OQ14 | partial | Keep export foreground-only with visible progress; do not promise background |
| `:media` playback | MP4 not finalized when the media source is built | **GAP** | **GAP** | Black frame. Gate on `VideoRecordEvent.Finalize`, show "preparing" |
| `:app` delete project | Export running, FGS holds handles | **GAP** | **GAP** | Partial delete. Cancel export first |

**3 critical gaps** (no test, no handling, silent): storage fill, recognizer silence,
playback before finalize.

---

## 10. Parallel lanes

| Lane | Modules | Depends on | Can start |
|---|---|---|---|
| **A** | `:engine` + `:engine-fixtures` | interfaces frozen | immediately, tonight |
| **B** | `:eval` | Lane A's types | after interfaces, before aligner |
| **C** | `:asr` | Lane A's `Recognizer`/`VoiceActivity` interfaces | immediately |
| **D** | `:npu` | Lane A's `VisionSignal` interface | immediately |
| **E** | `:capture` | Lane A's `AudioFrame`/`VideoAnchor` | immediately |
| **F** | `:media` | Lane A's `EditList` type | immediately |
| **G** | `:app` UI | `:engine-fixtures` replay only | immediately |
| **H** | `:link` (Tier 2) | Lane A types | immediately |

**Launch A through H in parallel the moment the interfaces in §4 are frozen.** Freezing
those ~120 lines is the only serial step in the whole build. Nothing conflicts because no
two lanes share a file.

**Conflict flags:** Lanes A and B both touch `:engine` types — B consumes, A defines, so
freeze §4 before starting B. Lane G and Lane A both define `MiscueType` presentation if
nobody is careful; the enum is A's, the presentation map is G's.

**Human assignment** (three humans, N agents each): T2 → Lanes A + B + C (the brain and its
evidence). T1 → Lanes E + F + D (capture, media, NPU). T3 → Lanes G + H (UI, multicam).
Device testing and gate checks are human-only and serialize; budget them explicitly.

---

## 11. Ranking, re-scored

Tier 3 was labelled "Finale backlog", but the Finale only happens if Chennai is won.
Ranking by "this is the Bengaluru headline" ranks by a conditional this weekend decides.
Criterion deleted; everything re-scored on **"does this help win Chennai."**

**No cut list. One ranking, worked top-down.** §10's Working Order rule already handles the
rest: everyone takes the highest-ranked open item, nothing below the line is deleted, and
the clock decides where the line falls at runtime instead of a tired person deciding at
16:20. This is a better answer to §9's agenda item 1 than the cut list it asked for.

**Promoted out of Tier 3 by the re-score:**

| Item | Was | Now | Why |
|---|---|---|---|
| #28 personal calibration | Tier 3 | high Tier 1 | Appendix A records Pune's evaluators asking "How is the agent self-learning?" verbatim. Thresholds are already runtime config, so per-creator offsets are cheap. Highest value per hour in the list |
| #36 brief-mode report | Tier 3 | Tier 2 | The VC is 1 of 3 jurors in the room Saturday. The per-deliverable report is the wrap report with a different header |
| #35 script check | Tier 3 | Tier 1 | Splitting over-long lines improves paste, the first thing a juror sees |

**Unchanged, but now for honest reasons:** #21 multicam is last on cost, not because it is
"the Finale headline". #23 Hinglish/Tanglish stays cut because no Chennai juror scores it.

---

## 12. Media3: verified API shapes (researched 2026-09-11)

Do not write these from memory. Several of the obvious answers are deprecated.

### Playback of the edit list

**`ConcatenatingMediaSource` is DEPRECATED.** Its own doc says "Use playlist modification
methods like `addMediaItem` instead." `ConcatenatingMediaSource2` exists but combines
everything into one `Timeline.Window` — use it only if you need the segments to be a single
item, which you do not.

The current idiom is the **playlist API plus `ClippingConfiguration`**:

```kotlin
MediaItem.Builder()
  .setUri(videoUri)
  .setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder()
      .setStartPositionMs(startMs)
      .setEndPositionMs(endMs)      // or TIME_END_OF_SOURCE
      .build())
  .build()
```

then `player.setMediaItems(items, resetPosition)`. Transitions between playlist items are
documented as seamless.

**THE 300 ms RISK, and it is real.** From the Media3 media-items guide:

> "If the start position is not aligned with a keyframe then the player will need to
> **decode and discard data from the previous keyframe up to the start position** before
> playback can begin. This will introduce a short delay at the start of playback, including
> when the player transitions to playing a clipped media source as part of a playlist."

This applies to **every segment boundary in the cut**, not just the first. With CameraX's
default encoder settings the GOP length is unknown to you. Mitigations, in order:
1. Snap cut in-points to keyframes where the pause is long enough to allow it.
2. Pre-warm the player before the Wrap tap.
3. Measure `stop-to-first-frame` and per-boundary hitching on the loaner and report it
   honestly, per Premise 9.

Also flagged: "Subtitles, clipping and ad insertion are only supported if you use
`DefaultMediaSourceFactory`."

**Architecture A's playback path** (fallback only): `MergingMediaSource` is still current
but `@UnstableApi`, and `adjustPeriodTimeOffsets` means *align both to zero*, **not** apply
an arbitrary offset. There is no fixed-offset parameter. To shift audio, compose:
`ClippingMediaSource` (use its `Builder`; raw constructors are deprecated) to trim the head,
or concatenate a `SilenceMediaSource(durationUs)` in front to delay it.

**`CompositionPlayer` exists** and plays a `Composition` in real time with no export step,
explicitly supporting "single video sequence + single audio sequence preview" — which is
Architecture A's playback, natively. **It is `@ExperimentalApi` and early preview.** Do not
bet the demo on it. Plain ExoPlayer plus playlist plus `ClippingConfiguration` is the safe
path.

### Export

Classes: `Transformer`, `Composition(.Builder)`, `EditedMediaItem(.Builder)`,
`EditedMediaItemSequence`, `Effects`, `ExportResult`, `ExportException`, `ProgressHolder`.
Overlays from `androidx.media3.effect`: `OverlayEffect` (applies `TextureOverlay`s in FIFO
order, last on top), `TextOverlay`, `BitmapOverlay`, `DrawableOverlay`, `CanvasOverlay`,
`StaticOverlaySettings`.

Multiple sequences (video from one file, audio from another) is documented and supported:

```kotlin
val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(v1, v2))
val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(track))
val composition  = Composition.Builder(videoSequence, audioSequence).build()
transformer.start(composition, path)
```

Note `forceAudioTrack`/`forceVideoTrack` are deprecated in favour of
`trackTypes.contains(C.TRACK_TYPE_AUDIO)`.

**Two export speedups, and why only one applies to you:**
- `experimentalSetMp4EditListTrimEnabled(true)` — trims via the MP4 edit list, no
  re-transcode. **Carries a documented privacy risk: the trimmed data is still in the file.**
  That directly contradicts The Vanish and P3. Do not use it for the cut.
- `experimentalSetTrimOptimizationEnabled(true)` — decodes and re-encodes as little as
  possible, but only for "single-asset MP4 input with no effects except no-op video effects
  and rotations divisible by 90 degrees." **A burned-in text caption disqualifies it.** It
  falls back automatically and reports via `ExportResult.OptimizationResult`.

**No published performance numbers exist** for Transformer at 1080p with overlays. Measure
it; do not quote Google's 720p-no-overlay benchmark on a slide.

---

## 13. Foreground services: verified constraints

| | `camera` | `microphone` | `mediaProcessing` |
|---|---|---|---|
| Manifest permission | `FOREGROUND_SERVICE_CAMERA` | `FOREGROUND_SERVICE_MICROPHONE` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` |
| Runtime prerequisite | `CAMERA` granted | `RECORD_AUDIO` granted | none |
| **Start from background?** | **No** | **No** | Yes |
| Daily cap | none | none | **6 h per 24 h** |

**The rule that will bite you: `camera` and `microphone` are while-in-use gated and cannot
be started from the background — on Android 14+ that is a `SecurityException` thrown
immediately at `startForeground()`, not a silent degradation.** Call
`startForegroundService()` while an activity is visible. `PermissionChecker
.checkSelfPermission()` does not protect you; it returns `PERMISSION_GRANTED` even in the
background.

**`mediaProcessing` 6-hour cap, confirmed.** After 6 h in 24, the system calls
`Service.onTimeout(int, int)` and the service has **a few seconds** to call `stopSelf()` or
the system throws `RemoteServiceException`. Further starts then fail with
`ForegroundServiceStartNotAllowedException` until the user foregrounds the app. You will not
approach 6 hours at a hackathon, **but the missing `onTimeout()` handler is an ANR waiting
to happen** — implement it anyway, it is four lines.

**Android 16 changes nothing** for these three types; Android 15's rules carry forward.

Given the background-start rule and OriginOS's aggressiveness, **keep export
foreground-only with visible progress.** Do not promise background export in the UI
(§5.2 step 12 currently says "Background export progress").

Test knobs: `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>`.

---

## 14. Storage: query it, do not estimate it

CameraX publishes **no** bitrate or MB-per-minute figure for any `Quality`. `Recorder`
defaults to `VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE`, so the bitrate is whatever vivo
put in the iQOO 15's `CamcorderProfile`. Get the real number in one line on the device:

```kotlin
CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P).videoBitRate
```

Add it to the bring-up harness. For live tracking during a session,
`RecordingStats.getNumBytesRecorded()` is exact and free — poll it on
`VideoRecordEvent.Status` and drive the storage-fill guard (T12) from it.
