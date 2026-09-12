# Landmines

Verified traps. Every one of these was researched on 2026-09-11 against real artifacts,
real source and real bug reports — they are not folklore. Each one fails **silently or at
runtime**, which is why they are worth a document: your laptop will not tell you.

Read the section for your module before you write its first line.

---

## L1 — sherpa-onnx below v1.13.8 produces EMPTY streaming output on SM8850

**Module:** `:asr`. **Severity:** kills the entire product, silently.

v1.13.7 and earlier return empty streaming-ASR results **forever** on Snapdragon 8 Elite
Gen 5. No crash, no exception, no log line. Chunk counts are correct. Decode call counts
are correct. The encoder numbers are wrong. Root cause is onnxruntime 1.27.x miscomputing
the zipformer2 encoder on that SoC (sherpa-onnx issue #3845).

- v1.13.7 pins onnxruntime 1.27.1 → **broken on the iQOO 15**
- v1.13.8 pins onnxruntime 1.28.2 → released 2026-09-10

**Swapping only `libonnxruntime.so` does not work** — `libsherpa-onnx-jni.so` requires the
versioned symbol `OrtGetApiBase@@VERS_1.27.0`. Take the whole v1.13.8 AAR.

**Prefer int8 encoders.** Only int8 is verified end to end under 1.28.x in a real streaming
pipeline; fp32 is unconfirmed.

**First thing on the loaner:** smoke-test ASR. A silent empty-output failure is exactly the
kind of thing that gets blamed on your own aligner for four hours.

## L2 — use the sherpa **static-link** AAR, and vendor it

`sherpa-onnx-static-link-onnxruntime-1.13.8.aar` is both safer and **7.5 MiB smaller** than
the default:

| Variant | arm64-v8a contents | Total |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` | `libonnxruntime.so` 21.2 MiB + c-api 4.3 + cxx-api 0.4 + jni 4.6 | 30.5 MiB |
| `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | `libsherpa-onnx-jni.so` only, 23.0 MiB | **23.0 MiB** |

The static build exports and imports **zero `Ort*` symbols**, so it cannot collide with
anything, now or later. Size matters because you sideload every build to three loaners over
Office Kit.

**The catch: you cannot get it from Gradle.** sherpa is not on Maven Central. JitPack
publishes `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`, but **`jitpack.yml` wraps
the DYNAMIC variant** — the convenient coordinate silently gives you the larger,
collision-prone build. Download the static release asset into `libs/`. Two minutes, and it
removes a whole failure class. See [`libs/README.md`](../../libs/README.md).

**Do not rename the package.** JNI symbols are hardcoded to `com.k2fsa.sherpa.onnx`.

**No `libc++_shared.so` collision** — sherpa statically links libc++. Verified.

## L3 — `CircularBuffer::Push` **exits the process** if the VAD buffer fills

**Module:** `:asr`. Not an exception. A hard process exit, at 60 seconds of un-popped
audio, and the Kotlin binding gives you no way to change the capacity. **Always `pop()`
promptly.**

Three more Silero VAD behaviours that will confuse you:

- **Speech start is backdated** ~314 ms
  (`start = max(tail - 2*windowSize - minSpeechDurationSamples, head)`). Good — no clipped
  onsets — but `start` is not the first speech frame.
- **Speech end is trimmed** to `tail - minSilenceDurationSamples`. Trailing silence is cut.
- **`maxSpeechDuration` defaults to 5 s and is a forced-split hack.** Past it the code
  *silently overrides your config* to `minSilenceDuration = 0.1` and `threshold = 0.90` to
  force a break. **Raise it.** Creators read long unbroken lines, and a silent config
  override mid-session is a debugging nightmare at 03:00.

There is no `end` field on `SpeechSegment`; derive it as `start + samples.size`. `start` is
a linear, never-wrapping index — which is why VAD, not ASR, owns audio cutting.

## L4 — ASR timestamps cannot splice audio

**Module:** `:asr`, `:engine`. **This invalidated two rules as originally designed.**

`OnlineRecognizerResult` gives `tokens: Array<String>` and `timestamps: FloatArray`. What
those actually are:

1. **A 40 ms grid** — `timestamps[i] = 0.04 * encoder_frame_index`. Nothing lands between.
2. **Emission peaks, not boundaries.** The frame at which the transducer *emitted* a
   symbol. No start, no end, no duration.
3. **BPE sub-word tokens.** `"YELLOW"` returns `" YE","LL","OW"` with three timestamps. You
   can derive a word *start* from the leading-space marker; **word end does not exist**.
4. **Trained with no delay penalty.** Emission lag is entirely unconstrained and **the
   magnitude is not documented anywhere.**
5. Forced alignment is an open request. The maintainer's answer to this exact question was
   "use Montreal Forced Aligner instead."

**Consequences, already applied to the design:**
- R3 (one-breath split) splits **coverage, not audio**. One utterance spanning lines N and
  N+1 marks both covered and emits **one** segment. Strictly better — no word boundary
  needed, and no splice inside natural speech.
- Filler splicing happens **only on VAD silences**, with >150 ms of silence on both sides.
- Take boundaries at VAD silences were always correct.
- R4's miscue reasons are unaffected — that is text-to-text alignment.

## L5 — the sherpa Kotlin binding drops absolute time

C++ computes `start_time` and `segment`, but the JNI constructor signature is
`(Ljava/lang/String;[Ljava/lang/String;[F[F)V` — text, tokens, timestamps, ysProbs.
**`start_time` is dropped on the floor.** After every endpoint reset, timestamps restart at
~0 and Kotlin cannot recover absolute stream time.

This is why the sample-index master clock is mandatory, not merely elegant: count every
sample you pass to `acceptWaveform`, snapshot the counter at each `reset()`, add
`snapshot / 16000.0` to every timestamp.

## L6 — `Accelerator.NPU` does not throw when the NPU is unavailable

**Module:** `:npu`. **This is the exact mechanism that made earlier teams in this series
ship on CPU while believing otherwise.** From LiteRT's own Kotlin source:

```kotlin
val accelerators =
  if (options.accelerators.size == 1 && options.accelerators.first() == Accelerator.NPU) {
    setOf(Accelerator.NPU, Accelerator.CPU)   // CPU silently appended
  } else { options.accelerators }
```

Every obvious way to check is a trap:

| Route | Verdict |
|---|---|
| `Environment.getAvailableAccelerators()` | ❌ **It lies.** Returned `[NPU, GPU, CPU]` where the Qualcomm plugin had failed to `dlopen`. It reports registration, not health |
| `NpuCompatibilityChecker.Qualcomm.isDeviceSupported()` | ❌ a `Build.SOC_MANUFACTURER` string comparison |
| Any getter on `CompiledModel` | ❌ none exists in Kotlin; `IsNonCpuFullyAccelerated()` is C++ only |
| Force the set to reach native as NPU-only and let `create` throw | ✅ **the only route** |

```kotlin
// size == 2, so the auto-CPU-append does not fire.
// Accelerator.NONE maps to a JNI no-op, so native receives {NPU} alone.
// create() then throws LiteRtException when delegation is not total.
CompiledModel.Options(setOf(Accelerator.NPU, Accelerator.NONE))
```

**The Kotlin route to this is undocumented — confirm it on-device before relying on it.**
`LiteRtException.status` is private with no getter, so Kotlin sees only the message string.

**Make the failure loud at app start.** A mandatory startup self-test that builds the model
NPU-only and lets the exception reach a red banner. Fail visibly on a laptop rather than
silently at 0.195 ms → 146 ms on stage. Corroborate with `adb logcat -s litert:V tflite:V`
— the string `TfLiteXNNPackDelegate` means you are on CPU. Any deliberate CPU fallback uses
a **different** `CompiledModel` instance and a **visibly different label**.

## L7 — pin LiteRT's AAR and its `.so` files to the same tag, and use `v81`

`implementation("com.google.ai.edge.litert:litert:2.2.0")` is the only Maven artifact.
There is no Qualcomm Maven artifact — the QNN libraries come from the **GitHub release zip
of the matching tag**: `litert_npu_runtime_libraries_jit.zip` from release v2.2.0.

**Never `litert:+`.** A mismatch between the AAR and the runtime `.so` presents as
`dlopen failed: cannot locate symbol "LiteRtQualcommOptionsGet"` followed by the app
running happily on CPU while logging "NPU initialised successfully". LiteRT issue #7891, on
real Snapdragon hardware.

**SM8850 needs `qualcomm_runtime_v81`.** Google's own blog example ships `v79`.

| Module | Chip |
|---|---|
| `qualcomm_runtime_v73` | 8 Gen 2 |
| `qualcomm_runtime_v75` | 8 Gen 3 |
| `qualcomm_runtime_v79` | 8 Elite |
| **`qualcomm_runtime_v81`** | **8 Elite Gen 5 — the iQOO 15** |

**Bypass Play dynamic-feature delivery.** The runtime libs ship as
`com.android.dynamic-feature` modules with `dist:install-time` device-group conditions — a
Play mechanism a sideloaded debug APK is not guaranteed to carry.
`BuiltinNpuAcceleratorProvider.getLibraryDir()` returns
`context.applicationInfo.nativeLibraryDir`, which is exactly where
`app/src/main/jniLibs/arm64-v8a/` lands. **Copy the nine `.so` files straight into
`jniLibs`** (plus the rehearsal phone's `v75`/`v73` Skel+Stub) and skip AI Packs entirely.
Keep `useLegacyPackaging = true`, `abiFilters "arm64-v8a"`, `minSdk 31`.

## L8 — AI Hub's Whisper and Qwen3 do **not** run through LiteRT

| Model | Format | Portable? | Through LiteRT? |
|---|---|---|---|
| Face-Det-Lite | TFLITE w8a8, Universal | ✅ | ✅ 0.195 ms, BSD-3 |
| FaceMap-3DMM | TFLITE w8a8, Universal | ✅ | ✅ 0.117 ms, BSD-3 |
| HRNet-Pose | TFLITE w8a8, Universal | ✅ | ✅ 0.516 ms, MIT |
| MiniLM-v2 | TFLITE float, Universal | ✅ | ✅ 0.482 ms, Apache-2.0 |
| **Whisper-Small / Large-V3-Turbo** | **QNN context binary, PER-CHIPSET** | ❌ | ❌ separate integration |
| **Qwen3-4B-Instruct** | **Genie / GENIEX, per-chipset** | ❌ | ❌ separate runtime |

The Whisper and Qwen3 downloads are literally named
`...-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip` and will not load on a rehearsal
phone at all. "NPU recognition" is a wholly separate runtime integration, not a switch.
This is why the CPU streaming transducer is the spine.

Two model gotchas that each cost a morning:

- **Face-Det-Lite input is `[1, 480, 640, 1]` uint8 GRAYSCALE, not RGB.** The AI Hub page
  says "480x640"; the export's `metadata.json` says `color_format: "grayscale"`. ~967 KB
  unzipped, downloadable unauthenticated from the public S3 bucket.
- **MiniLM-v2 exports the transformer only.** 384-dim output, 128-token input. You
  implement WordPiece tokenisation, mean-pooling and L2 normalisation yourself; without the
  normalisation the vectors are not cosine-comparable. Its `w8a8` variant is *slower* than
  float (0.945 vs 0.482 ms) — use float.

## L9 — concurrent audio capture is the highest-value 20 minutes of the build

**Module:** `:capture`. **Probe this before writing any feature code.**

Architecture B (primary) lets CameraX mux its own audio; Architecture A (fallback) disables
CameraX audio and owns sync itself. Android's own docs settle which to build:

> "When buffers from a REALTIME device are passed **directly to a video encoder from the
> camera**, automatic compensation is done to account for differing timebases of the audio
> and camera subsystems. **If the application is receiving buffers and then later sending
> them to a video encoder** […] **this compensation is not present.**"

**Neither is documented-safe.** B's failure is loud, instant and detectable in code; A's is
a silent constant offset you discover on stage. Build B.

The probe: start CameraX with `withAudioEnabled()`, start a `VOICE_RECOGNITION`
`AudioRecord`, run 10 s, assert **all three**:

1. `recordingStats.audioStats.audioState == AUDIO_STATE_ACTIVE` and `getAudioAmplitude() > 0`
2. `AudioRecord` buffers are **not all zero** — check the bytes directly
3. `AudioRecordingConfiguration.isClientSilenced() == false`

**Run both start orders.** The CDD's tiebreak for equal-priority capturers is "most
recently started wins"; if OriginOS reuses that path for same-app clients, order decides
who gets audio.

**Do not trust the callback alone.** `registerAudioRecordingCallback()` must be registered
*before* `startRecording()`, and it fires "only when the app is receiving audio and a
change occurs" — silence from frame zero may never fire it.

**Write the WAV unconditionally**, even under B where you do not need it. Then the fallback
is a playback-path swap, not a re-architecture at hour 20.

If you land in A, the clock pairing is documented — read `SENSOR_INFO_TIMESTAMP_SOURCE`,
then `UNKNOWN → TIMEBASE_MONOTONIC` (`System.nanoTime()`), `REALTIME → TIMEBASE_BOOTTIME`
(`SystemClock.elapsedRealtimeNanos()`). The remaining hole is that **CameraX exposes no
wall-clock anchor for MP4 t=0** at all.

## L10 — the keyframe problem is what threatens "no render bar"

**Module:** `:media`. From the Media3 media-items guide:

> "If the start position is not aligned with a keyframe then the player will need to
> **decode and discard data from the previous keyframe up to the start position** before
> playback can begin."

This applies to **every segment boundary in the cut**, not just the first, and with
CameraX's default encoder settings the GOP length is unknown to you. Mitigations in order:
snap in-points to keyframes where the pause allows; pre-warm the player before the Wrap
tap; **measure `stop-to-first-frame` and per-boundary hitching on the loaner** and report
it honestly rather than assuming 300 ms.

## L11 — Media3 APIs: several obvious answers are deprecated or disqualified

- **`ConcatenatingMediaSource` is DEPRECATED.** Use the playlist API
  (`player.setMediaItems`) plus `MediaItem.ClippingConfiguration`.
  `ConcatenatingMediaSource2` combines everything into one `Timeline.Window` — not what you
  want.
- **`CompositionPlayer` exists** and previews a `Composition` with no export step, which is
  Architecture A's playback natively. It is `@ExperimentalApi` and early preview. **Do not
  bet the demo on it.**
- **`MergingMediaSource`'s `adjustPeriodTimeOffsets` means *align both to zero*,** not
  apply an arbitrary offset. There is no fixed-offset parameter. To shift audio, compose a
  `ClippingMediaSource` (use its `Builder`; raw constructors are deprecated) or concatenate
  a `SilenceMediaSource` in front.
- "Subtitles, clipping and ad insertion are only supported if you use
  `DefaultMediaSourceFactory`."
- `forceAudioTrack` / `forceVideoTrack` are deprecated in favour of
  `trackTypes.contains(C.TRACK_TYPE_AUDIO)`.

**Two export speedups, and neither applies to you:**

- **`experimentalSetMp4EditListTrimEnabled(true)` — DO NOT USE.** It trims via the MP4 edit
  list with no re-transcode, and **the trimmed data is still in the file.** That makes The
  Vanish a lie and P3 false, and the infosec juror can open the exported file.
- `experimentalSetTrimOptimizationEnabled(true)` only applies to "single-asset MP4 input
  with no effects except no-op video effects and rotations divisible by 90 degrees" — a
  burned-in caption disqualifies it.

**No published performance numbers exist** for Transformer at 1080p with overlays. Measure
it; Google's 720p-no-overlay benchmark is not your number.

## L12 — foreground services: `camera` and `microphone` cannot start from the background

**Module:** `:capture`, `:media`.

| | `camera` | `microphone` | `mediaProcessing` |
|---|---|---|---|
| Manifest permission | `FOREGROUND_SERVICE_CAMERA` | `FOREGROUND_SERVICE_MICROPHONE` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` |
| Runtime prerequisite | `CAMERA` granted | `RECORD_AUDIO` granted | none |
| **Start from background?** | **No** | **No** | Yes |
| Daily cap | none | none | **6 h per 24 h** |

On Android 14+ a background start is a `SecurityException` thrown immediately at
`startForeground()`, not a silent degradation. Call `startForegroundService()` while an
activity is visible. `PermissionChecker.checkSelfPermission()` does **not** protect you —
it returns `PERMISSION_GRANTED` even in the background.

`mediaProcessing`'s 6-hour cap calls `Service.onTimeout(int, int)` and gives you a few
seconds to `stopSelf()` or the system throws `RemoteServiceException`. You will not
approach 6 hours at a hackathon, **but a missing `onTimeout()` handler is an ANR waiting to
happen** — it is four lines, implement it.

**Keep export foreground-only with visible progress.** Do not promise background export in
the UI.

## L13 — storage: query the bitrate, do not estimate it

CameraX publishes **no** MB-per-minute figure for any `Quality`. `Recorder` defaults to
`VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE`, so the bitrate is whatever vivo put in the
iQOO 15's `CamcorderProfile`. One line on the device:

```kotlin
CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P).videoBitRate
```

For live tracking, `RecordingStats.getNumBytesRecorded()` is exact and free — poll it on
`VideoRecordEvent.Status`. **A full disk mid-session currently truncates silently. That is
one of three known critical gaps** — see [`requirements.md`](requirements.md#known-gaps).

## L14 — thermal is less dangerous than assumed, but say so honestly

Nothing in Android or the QNN runtime documents *refusing* NPU work at
`THERMAL_STATUS_SEVERE`. The QNN HTP backend docs contain zero occurrences of "thermal",
"throttl" or "temperature". Android's NPU Manager can request a model unload on a thermal
spike, but that is **Android 17+** and the iQOO 15 runs Android 16.

So clocks drop and latency rises; nothing hard-refuses. Therefore:

- Use `QualcommOptions(htpPerformanceMode = SUSTAINED_HIGH_PERFORMANCE)`, not `BURST`.
- Register a thermal listener so the stage overlay can say "SEVERE, NPU still running,
  0.4 ms" instead of going quiet. That is a better moment than hiding it.
- **R9 reports a per-inference time and a count, never a latency SLA**, and if the count is
  zero the log names the thermal status that caused it. "Never zero" was a promise about
  hardware we do not control.

## L15 — threading in `:asr`

`OnlineStream` is internally mutex-guarded on every public method. `OnlineRecognizer`,
`Vad` and `KeywordSpotter` carry **no such locks** — one instance, one thread.
`numThreads` defaults to 1 and is the ORT intra-op count *per engine*, so three engines at
2 threads is six ORT threads competing on a phone.

The shape: one `AudioRecord` reader thread fans the same buffer to three consumers. VAD and
KWS run synchronously on that thread (both are tiny). ASR chunks go onto a bounded queue
drained by one dedicated thread. **Never share an `OnlineStream` between the recognizer and
the spotter.**

**Do not gate keyword spotting behind VAD segments** — it adds the full
`minSilenceDuration` finalisation delay (250 ms default) before the spotter sees any audio.

**Undocumented and very useful:** `createStream(keywords)` accepts runtime keywords with
`/` as the line separator, *appended to* the ones from `keywordsFile`. The command phrase
list can be tuned without a rebuild — exactly what Red Light needs.

The keyword file format is BPE tokens, space-separated, then optional `:<boost>` and
`#<threshold>` with **no space after the colon or hash**:

```
▁HE LL O ▁WORLD :1.5 #0.35
```

Generate it on a laptop with `sherpa-onnx-cli text2token --tokens-type bpe --bpe-model …`
and check the file in — it is not an on-device step.

---

## Unmeasured numbers

Do not design around a number nobody published. Each of these is a "measure it in hour
one" item, not an assumption:

| Number | Status |
|---|---|
| Streaming zipformer RTF on modern ARM64 | **Not published.** Desktop x86 is 0.038 (int8, 1 thread). The whole UX depends on this |
| Transducer emission lag | **Not documented anywhere** |
| Media3 Transformer at 1080p with overlays | **No published numbers.** Google's benchmark is 720p with no overlays |
| `stop-to-first-frame` for the cut | Assume nothing. It is the "no render bar" claim |
| CameraX MB/min at 1080p on this device | Query `CamcorderProfile`, do not estimate |
| Whether concurrent capture works on this device | L9's probe |
| Whether the `Accelerator.NONE` trick works in Kotlin | L6, undocumented |

The model is LibriSpeech-only — read audiobook speech — so expect degradation on
spontaneous and accented delivery. That is a live risk for Indian-accented English and it
should show up in the held-out numbers. **Measure it; do not assume it.**
