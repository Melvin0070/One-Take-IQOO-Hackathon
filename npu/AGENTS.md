# :npu — the NPU

LiteRT + the Qualcomm accelerator. **The only module in the repo that links QNN.**
Implements `:engine`'s `VisionSignal`.

## Read `docs/agents/landmines.md` L6–L8 first

**L6 is the one that matters most: `Accelerator.NPU` does not throw when the NPU is
unavailable.** LiteRT silently appends CPU when NPU is the only accelerator requested.
Every obvious way to check lies:

- `Environment.getAvailableAccelerators()` returned `[NPU, GPU, CPU]` on a device where the
  Qualcomm plugin had failed to `dlopen`. It reports registration, not health.
- `NpuCompatibilityChecker.isDeviceSupported()` is a `Build.SOC_MANUFACTURER` string compare.
- No getter on `CompiledModel` exists in Kotlin.

**The only route** is to force the set so native receives NPU alone and let `create` throw:

```kotlin
// size == 2 so the auto-CPU-append does not fire; NONE is a JNI no-op.
CompiledModel.Options(setOf(Accelerator.NPU, Accelerator.NONE))
```

This Kotlin route is **undocumented** — confirm it on-device before relying on it.

**This is the exact mechanism that made earlier teams in this series ship on CPU while
believing otherwise.** Make the failure loud: a mandatory startup self-test that builds the
model NPU-only and lets the exception reach a red banner. Corroborate with
`adb logcat -s litert:V tflite:V` — `TfLiteXNNPackDelegate` in the log means CPU.

**Any deliberate CPU fallback uses a different `CompiledModel` instance and a visibly
different label.** Never a fallback that reuses the "NPU" label. That single rule is what
this module exists to protect.

## Versions and runtime libs

`com.google.ai.edge.litert:litert:2.2.0` is the only Maven artifact. The QNN libraries come
from the **GitHub release zip of the same tag** — `litert_npu_runtime_libraries_jit.zip`.

**Never `litert:+`.** A mismatch presents as `dlopen failed: cannot locate symbol
"LiteRtQualcommOptionsGet"`, followed by the app running happily on CPU while logging "NPU
initialised successfully".

**SM8850 needs `qualcomm_runtime_v81`.** Google's own example ships `v79` — wrong Hexagon
version for the iQOO 15.

**Skip Play AI Packs.** The runtime libs ship as dynamic-feature modules with `install-time`
device-group conditions, which a sideloaded debug APK is not guaranteed to carry. Copy the
nine `.so` files into `app/src/main/jniLibs/arm64-v8a/` — that is exactly what
`getLibraryDir()` returns. Keep `useLegacyPackaging = true`, `abiFilters "arm64-v8a"`,
`minSdk 31`.

## Models

| Model | Notes |
|---|---|
| **Face-Det-Lite** | **`[1, 480, 640, 1]` uint8 GRAYSCALE, not RGB.** ~967 KB, 0.195 ms on 8 Elite Gen 5, BSD-3 |
| FaceMap-3DMM | 0.117 ms, BSD-3 |
| HRNet-Pose | 0.516 ms, MIT |
| MiniLM-v2 | 0.482 ms float — **its w8a8 variant is slower.** Exports the transformer only: you implement WordPiece, mean-pooling and L2 normalisation yourself, and without the normalisation the vectors are not cosine-comparable |

**Whisper and Qwen3 from AI Hub do NOT run through LiteRT.** They are per-chipset QNN
context binaries and a wholly separate runtime integration. This is why the CPU streaming
transducer is the spine and NPU speech was never the cheap upside it looked like.

## The signal must do a real job

A vision model that only draws a chip reads as a prop to a CTO — and that is 15% of the
rubric. Its job is the **off-frame note on a take**, which loses to any other clean take in
edit-list selection. Never a flag, never marks a line needed. That is R9's whole argument.

## Thermal

Nothing in Android or QNN documents *refusing* NPU work at `THERMAL_STATUS_SEVERE`; clocks
drop, nothing hard-refuses. So:

- `SUSTAINED_HIGH_PERFORMANCE`, not `BURST`, for a long session.
- Register a thermal listener so the stage overlay can say "SEVERE, NPU still running,
  0.4 ms" rather than going quiet. That is a better moment than hiding it.
- **Report a per-inference time and a count, never a latency SLA.** If the count is zero,
  the log names the thermal status that caused it.

## The overlay

You own the data behind the engine-stats overlay, and it goes on stage: model, processor,
µs per inference, verdict latency, thermal state, **flag-vector checksum and config
version** (R25).
