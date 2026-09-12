# iQOO 15 inference framework

> Historical prototype record: the implementation and measurements described here are archived under `experiments/` and excluded from the current nine-module build.
> Run the historical build commands from the [tested prototype checkout](../experiments/AGENTS.md), not from the current repository root.

This change starts [issue 1](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/1).
It does not complete the requirement to execute all production neural models on the NPU.
The target is the owner’s iQOO 15, identified as I2501 / SM8850 with Android 16.

## Architecture

`:engine` owns model-specific backend selection and serialized inference sessions.
The default policy is `NPU_PREFERRED`; `NPU_REQUIRED` rejects CPU and mixed graph execution, and `CPU_ONLY` provides a same-phone baseline.
Backend preparation and successful execution are different report states.
An execution failure invalidates the session instead of replaying the same input on another backend, which protects stateful VAD and caption processing.
Native release is attempted at most once because a thrown close may have already freed its handle.
Release failures are explicit diagnostics and stop preparation fallback.
Silero uncertainty remains a successful computation with an `UNKNOWN` speech result; the JNI boundary now distinguishes it from a computation failure.
Existing task adapters retain native cancellation, model verification, frame validation, and resource ownership.

The archived `experiments/recorder-engine-android/` module supplied Android runtime integration.
`QnnRuntimeProbe.inspect(context)` opens the packaged HTP backend, selects the compatible QNN interface, creates backend/device handles, and releases them.
Call it from a background thread.
`RUNTIME_READY` means initialization succeeded; `modelExecutionVerified` remains false because this probe never executes a model.
No production backend may infer model compatibility from this result.

## Local SDK build

Vendor headers and libraries remain outside version control.
The tested SDK is QAIRT 2.50.0.260828 with matching V81 host stub and DSP skeleton.
Set `QAIRT_SDK_ROOT` to a local SDK directory that the Gradle JVM can read.
On this Mac, the build process could not access Downloads, so the required SDK files were staged under the user’s Library/Caches directory.
Do not copy vendor platform libraries such as `libcdsprpc.so` into the APK.
The library manifest declares access to the device’s existing driver.

The following command is from the archived prototype checkout:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
QAIRT_SDK_ROOT=/path/to/qairt/2.50.0.260828 \
./gradlew :engine:test :engine-android:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

A normal build without `QAIRT_SDK_ROOT` remains supported for development and reports `SDK_NOT_CONFIGURED` when probing this phone.
Providing an incomplete SDK fails the build with the missing required file.
The SDK-enabled APK extracts native libraries so the QNN loader and DSP driver can open the V81 skeleton by path.

Install with `adb install -r`, preserving private recordings and the caption model.
Do not use the connected Gradle test runner, which can uninstall the app.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w -r -e requireQnn true \
  -e class com.example.one_take.QnnRuntimeProbeTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

Omit `-e requireQnn true` when testing a build made without the SDK.
A successful probe is deliberately insufficient to close issue 1.

## Remaining model work

| Pipeline | Current artifact | Required work |
| --- | --- | --- |
| Captions | Multilingual Whisper tiny GGML | Convert encoder and autoregressive decoder, port token/timestamp handling, verify transcription and word timing |
| Pause detection | Silero v6.2 GGML | Convert the recurrent graph, preserve state/context and 512-sample framing, verify speech decisions |
| Face tracking | MediaPipe face landmarker task | Validate delegated component graphs and reproduce task preprocessing, tracking, and landmark geometry |

Qualcomm’s older [QIDK Whisper reference](https://github.com/qualcomm/qidk/tree/master/Solutions/NLPSolution3-AutomaticSpeechRecognition-Whisper) includes an SM8850 encoder path but uses a separate TFLite decoder.
The separate [Voice AI ASR integration](https://github.com/qualcomm/qidk/blob/master/GenAI-Solutions/ASR-LLM-TTS/readme_assets/asr_Readme.md) documents both encoder and decoder HTP artifacts and SM8850/V81 support.
That newer path is the selected caption integration candidate; neither reference establishes execution or caption timing parity in this app.
The [official Silero source](https://github.com/snakers4/silero-vad/tree/v6.2) provides ONNX assets that are conversion candidates; their compatibility with this HTP runtime has not been established.
The [MediaPipe Android API](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android) does not expose a QNN delegate for the complete task pipeline.
The local QAIRT conversion tools target supported Linux/Windows hosts; conversion on this macOS host is not established.

No HTP-ready production replacement artifact or output-parity result is included in this change.
Do not replace multilingual captions with an English-only model or accept timestamp/geometry regressions merely to obtain NPU activity.
The remaining acceptance evidence is actual graph placement and task quality for all three production paths, followed by full recorder validation and CPU/NPU measurements on this same phone.

## Device evidence

On 2026-09-12, the first app-level probe failed at device creation with QnnDsp transport setup errors.
Declaring the vendor native library and setting the DSP skeleton search path resolved that failure.
The SDK-enabled probe then passed with `RUNTIME_READY` on the physical iQOO 15.
The build without the SDK also passed its device test with `SDK_NOT_CONFIGURED`.
Neither test executes a neural graph or measures NPU utilization.

## Final verification

The SDK-enabled build passed 212 unit tests: 75 engine, 3 Android runtime, and 134 app tests.
App lint reported 0 errors and 30 existing warnings; Android runtime lint reported no issues.
Both APKs and both configured native ABIs built successfully.

The following suite passed 9 tests in 12.776 seconds on the iQOO 15, with no failures or skipped tests:

```sh
adb shell am instrument --user 0 -w -r -e requireQnn true \
  -e class com.example.one_take.QnnRuntimeProbeTest,com.example.one_take.InferenceFallbackTest,com.example.one_take.FaceInferenceDeviceTest,com.example.one_take.StreamingEngineTest,com.example.one_take.SileroSpeechClassifierTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

This includes three consecutive HTP initialization/release calls, actual Whisper and Silero inference, strict NPU rejection, the recorded-room pause fixture, and face inference through the real camera preview.
All three production model reports correctly identify CPU execution and `NPU_MODEL_UNAVAILABLE` fallback.

Caption transcription, persistence, and MP4 export passed separately in 5.826 seconds:

```sh
adb shell am instrument --user 0 -w -r \
  -e class com.example.one_take.CaptionMilestoneTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

An initial combined run timed out during export after the camera test closed its Activity.
A repeat combined run completed all 10 tests only after the app was brought back to the foreground, taking 253.096 seconds.
That assisted run is not evidence of unattended background export.
Use the separate instrumentation invocation above for the recorded passing result; the test-order/background-export interaction remains unisolated.
No exporter behavior was changed in this PR.

The first pause-fixture run exposed incorrect retirement of valid uncertain Silero frames.
After distinguishing uncertainty from JNI failure, the same recorded-room regression test passed.
The final checks do not establish NPU model quality, NPU utilization, improved performance, or complete recorder UI coverage.

## Model replacement delivery

[Issue #3](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/3) tracks Qualcomm multilingual Whisper Tiny through Voice AI ASR / QAIRT.
[Issue #4](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/4) tracks evaluating Qualcomm neural VAD, with Silero ONNX conversion as the alternative.
[Issue #5](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/5) tracks Qualcomm face detector and landmark components.
Each pipeline requires its own adapter and physical-device acceptance evidence.
The bundled Qualcomm VAD library does not establish its execution backend or suitability for editor pause suggestions.

The first caption prerequisite is the local bundle verifier in [tools/model-bundles](../tools/model-bundles/README.md).
It checks declared target metadata and file integrity without loading native code or marking the model NPU-compatible.
Runtime and tensor compatibility, distribution rights, graph placement, and output quality remain separate checks.

## Direct QNN graph execution follow-up

The [Whisper graph runtime](qualcomm-whisper-graphs.md) adds engine-owned HTP context execution using public encoder and decoder binaries.
Its device tests execute both graphs and verify strict NPU reports.
The recorder’s complete caption path remains on CPU until audio features, decoding, timestamps and quality validation are connected.
