# Historical recorder prototype verification

> Historical prototype record: the implementation and measurements described here are archived under `experiments/` and excluded from the current nine-module build.
> Run the historical build commands from the [tested prototype checkout](../experiments/AGENTS.md), not from the current repository root.

## iQOO 15 inference framework, 2026-09-12

The SDK-enabled debug build and instrumentation APK build passed on macOS using Android Studio’s JBR and QAIRT 2.50.0.260828.
Unit tests passed with no failures, errors, or skips: 75 engine, 3 Android runtime, and 134 app tests, totaling 212.
App lint has 0 errors and 30 existing warnings; the new Android runtime module has no lint issues.

Nine inference/device tests passed on I2501 / SM8850 with Android 16 in one unattended invocation.
They cover repeated app-level HTP initialization, actual CPU inference and fallback reports for all three production models, strict NPU rejection, and native recorded-room pause detection.
Caption transcription and export passed in a separate invocation.
A combined run required foregrounding the app to finish export; this is not counted as unattended background-export validation.

The models still execute on CPU.
This is an engine/framework foundation with verified HTP runtime access, not completion of NPU model integration.
See [inference architecture, exact commands, results, and remaining work](iqoo15-inference.md).
No user recordings were deleted or app data cleared.

## Earlier recorder and monitoring baseline

Verified on 2026-09-12 on macOS ARM64 using Android Studio JBR 25.0.3 and Python 3.13.7.
The connected device was an iQOO I2501 running Android 16 / SDK 36.
The app identity is `com.example.one_take`.

### Build and automated checks

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :engine:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -m unittest discover -s tools/device-monitor -p 'test_*.py' -v
```

The engine suite passed 56 tests and the app unit suite passed 131 tests, with no failures, errors, or skipped tests.
The monitoring suite passed 70 tests.
Lint completed with 0 errors, 30 warnings, and 2 hints.
Both the debug app APK and instrumentation APK built successfully.
All seven media fixtures were verified byte-for-byte inside the instrumentation APK.

Both ARM64 and x86-64 native libraries built successfully.
The packaged libraries expose all 11 JNI entry points under the correctly escaped `com_example_one_1take` prefix.
Bundled model bytes match the pinned hashes in the model documentation.
Temporary logs containing recognized speech text were removed from the caption bridge, and the rebuilt libraries no longer contain their log tag.

### Device checks

The app and test APKs were installed with `adb install -r`.
Direct instrumentation passed all four tests in `NativeSpeechClassifierTest` and `SileroSpeechClassifierTest`, including native inference on the checked-in speech and room fixtures.

The broader recorder UI run did not complete after opening the camera settings sheet in `CameraLayoutTest`.
It is not counted as a passing end-to-end suite.
An initial run also used a test APK missing the fixtures; packaging was corrected and the native tests were rerun successfully.
The streaming-caption test requires the downloadable model and was initially attempted before that prerequisite was installed.

A direct app walkthrough opened camera settings and the feature marketplace.
The model download reached 11 percent with visible progress updates, then failed with `java.net.ProtocolException: unexpected end of stream` from the HTTP response body.
The partial file was discarded and the UI showed the network error with a Retry download action.
This is a reproduced network-transfer failure, not a successful fresh model installation.
For runtime validation, the existing app's cached public model was copied after checking its exact 77,691,713-byte size and pinned SHA-256.
The original app and its model were preserved.
With that verified model, `StreamingEngineTest` and `CaptionMilestoneTest` both passed on the device in 14.565 seconds.
Those tests cover streamed recognition, native caption objects, caption persistence, rendered MP4 output, and preservation of the original synthetic video.

### Monitoring

Device capability probing completed successfully.
A bounded three-second capture produced four samples and completed with no collector errors.
The dashboard command successfully generated a JSON summary from that recording.
The recording, probe, and summary remain outside the repository.
The Perfetto browser interface and native system-trace capture were not re-exercised in this check.

### Source integrity

All 296 selected source-project files are represented in this project.
App packages, native class paths, JNI symbols, themes, and the visible app name use the One-Take identity.
The original source directory was preserved.
Vendored native sources, license notices, model files, test fixtures, and monitoring tools retain their source bytes.
Build output, local SDK settings, IDE session state, downloaded UI caches, and recorded device traces are excluded.
The existing Gradle wrapper was retained; its source-project counterpart differs only in its generated date comment.
The destination retains its existing core Android and Android-test dependency versions while adding the recorder dependencies.
Historical documents describe their original verification runs and may reference the original package name.
