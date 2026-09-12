# Qualcomm Whisper graph execution

This implementation targets the owner’s iQOO 15, I2501 / SM8850 / V81.
The tested firmware fingerprint is `iQOO/I2501i/I2501:16/BP2A.250605.031.A3_V000L1/compiler251211075749:user/release-keys`.
It adds the native graph execution layer for [issue #3](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/3).
The production caption adapter still requires audio features, autoregressive decoding, timestamps, cancellation and task-level validation before activation.

## Artifact source

The official [Whisper Tiny model card](https://huggingface.co/qualcomm/Whisper-Tiny) links the [public v0.61.0 Voice AI bundle](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_tiny/releases/v0.61.0/whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip) for Snapdragon 8 Elite Gen 5.
Its metadata identifies an SM8850/V81 reference device and QAIRT 2.45.0.260326154327 compilation.
This reference-device label is not evidence of iQOO compatibility by itself.
The bundle contains FP16 encoder and decoder graphs, vocabulary and metadata.
The proprietary Voice AI SDK is separate and requires Qualcomm account access.
This direct QNN implementation uses the existing QAIRT runtime without that SDK.

| File | Bytes | Observed SHA-256 |
| --- | --- | --- |
| encoder.bin | 20025344 | c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25 |
| decoder.bin | 97615872 | 45418a0c81c8964f2d1448e03f5ce35cd01daa4de19269962fd0414547cccccd |
| vocab.bin | 357313 | 0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b |

These hashes pin the downloaded files used for validation; they are not publisher-signed authenticity proofs.
Keep binaries and vendor SDK libraries outside version control.

## Runtime boundary

`QnnGraphSession` loads a context binary through QNN HTP, exposes tensor metadata and executes raw typed buffers.
Callers must supply the tensor’s declared native representation and exact byte count.
`QnnGraphBackend` pins a model specification and binary digest, then exposes named input/output buffers through the existing engine policy and reporting lifecycle.
No CPU runtime is selected by this adapter.
A successful preparation report only means the context was loaded.
Only a successful execution report identifies an executed graph.
The graph adapter does not mark the application’s full caption model validated.

## Local build and device checks

Use the installed QAIRT SDK with QnnSystem and the existing HTP libraries.

```sh
ANDROID_HOME=/path/to/android-sdk \
JAVA_HOME=/path/to/jdk \
QAIRT_SDK_ROOT=/path/to/qairt \
./gradlew :engine:test :engine-android:testDebugUnitTest :engine-android:lintDebug \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Install both APKs with `adb install -r`; never uninstall the app to run these checks.
Stage encoder.bin and decoder.bin under the app-private `files/qnn-whisper-test/` directory.
The opt-in test checks the exact hashes above before loading either graph.
For a local extracted bundle, stage only those test model files:

```sh
adb shell run-as com.example.one_take mkdir -p files/qnn-whisper-test
adb exec-in run-as com.example.one_take sh -c 'cat > files/qnn-whisper-test/encoder.bin' < /path/to/bundle/encoder.bin
adb exec-in run-as com.example.one_take sh -c 'cat > files/qnn-whisper-test/decoder.bin' < /path/to/bundle/decoder.bin
```

This leaves existing recordings and the installed caption model intact.

```sh
adb shell am instrument --user 0 -w -r \
  -e requireWhisperGraphs true \
  -e class com.example.one_take.QnnWhisperGraphTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

Without the opt-in argument, the graph tests skip rather than assume local models are installed.
A missing or incorrect model is a failure when explicitly opted in.
The test checks encoder outputs, two decoder state steps, repeated context release/reopen, digest rejection and strict engine execution reports.
Synthetic tensor inputs exercise graph execution, not transcription quality.

## Reference runner evidence

On 2026-09-12, QAIRT 2.50 qnn-net-run successfully loaded and executed both graphs on I2501.
The decoder consumed the encoder’s cross-attention outputs and produced 51,865 finite FP16 logits.
Detailed QNN profiles include accelerator execution and operation-cycle events.
The first decoder attempt failed because the input list used `.raw` instead of the runner’s `_native.raw` filenames; correcting the list resolved it.
These single cold executions are not latency benchmarks or caption-quality evidence.

## SDK-absent validation

A build with `QAIRT_SDK_ROOT` unset must contain the graph stub and no vendor QNN libraries.
Run the explicitly opted-in absence test on that build:

```sh
adb shell am instrument --user 0 -w -r \
  -e requireNoQnn true \
  -e class com.example.one_take.QnnGraphUnavailableTest,com.example.one_take.QnnRuntimeProbeTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

The first absence validation passed both tests and APK inspection found no `libQnn*` entries.
The runtime probe reported SDK_NOT_CONFIGURED and graph opening reported that QAIRT was not configured.

## Final app validation

The SDK-enabled build passed 216 unit tests: 75 engine, 7 Android runtime, and 134 app tests.
Android runtime lint reported no issues; app lint reported 0 errors and 30 existing warnings.
Both configured native ABIs and both debug APKs built successfully.

The following suite passed 12 tests in 15.065 seconds on the iQOO 15:

```sh
adb shell am instrument --user 0 -w -r \
  -e requireQnn true -e requireWhisperGraphs true \
  -e class com.example.one_take.QnnRuntimeProbeTest,com.example.one_take.QnnWhisperGraphTest,com.example.one_take.InferenceFallbackTest,com.example.one_take.FaceInferenceDeviceTest,com.example.one_take.StreamingEngineTest,com.example.one_take.SileroSpeechClassifierTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

The graph checks passed with encoder-to-decoder transfer, repeated context creation/release, strict NPU execution reporting, corrupt context rejection, missing graph rejection, hash rejection and closed-session rejection.
The first app run failed because it attempted to finalize an already finalized cached graph.
Removing that extra finalization call resolved the failure.
The existing CPU production paths still passed their inference, camera-preview and recorded-room pause checks.

This change does not activate NPU captions in the recorder.
It does not yet implement PCM-to-mel features, token decoding, word timestamps, long-recording chunking or caption cancellation on this backend.
Synthetic graph tests do not establish transcript quality or performance improvement.
Only V2 tensor metadata with static dimensions is accepted.
The engine adapter hashes a private snapshot and loads that same snapshot to avoid artifact-replacement races.
Native allocation and vendor release failures were reviewed but were not injected on the physical device.
If vendor context release fails, cleanup can leave vendor-owned resources allocated; automatic recovery from that state is not established.
