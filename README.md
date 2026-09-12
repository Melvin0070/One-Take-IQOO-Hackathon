# One-Take

A Kotlin Android app using Jetpack Compose and CameraX.
It records video with audio, lets you review it, and keeps recordings in a local library.

## Device monitoring tool

The standalone [Device Monitor](tools/device-monitor/README.md) collects Android device measurements over ADB, exports NDJSON for AI analysis, and hosts the actual Perfetto UI locally for native traces and sampled sessions, with JSON session comparisons.
It runs on the development computer and does not require changes to the recorder app.
Metric availability depends on the connected phone; GPU and NPU utilization require future vendor integrations.

## Project structure

- `engine/` is a pure Kotlin JVM module for session state, sample-based time, reversible edits, deterministic event replay, and inference backend policy.
- `engine-android/` contains Android runtime integration, including the iQOO 15 QNN/HTP capability probe.
- `app/.../engine/` adapts existing caption/cut metadata to the engine and persists checksummed event journals.
- `MainActivity.kt` starts the Compose interface.
- `RecorderContent.kt` coordinates navigation and screen state.
- `CameraRecorder.kt` owns CameraX and the authoritative recording state.
- `CaptureState.kt` defines idle, starting, recording, and finalizing states.
- `VideoStore.kt` manages recordings and interrupted-file recovery.
- `PermissionGate.kt` requests camera and microphone access.
- `CameraScreen.kt`, `ReviewScreen.kt`, and `LibraryScreen.kt` display the three screens.
- `RecorderUtils.kt` formats the recording timer.
- `res/values/strings.xml` contains user-facing text.

## Camera controls

The camera uses a large preview with black framing and an outlined record button.
Tap the top-left menu or quality badge to choose recording quality and toggle the composition grid or sensor level.
These settings persist between launches.
Available resolutions come from the selected camera, and the badge shows the active resolution.
Tap the zoom ratio to reveal a slider, or pinch the preview to zoom.
Use the flip control to switch cameras and the top-right thumbnail to open saved videos.
The camera screen keeps the display awake while it is visible.

## Recording behavior

Recordings are saved in the app's private `files/videos` directory.
The Saved videos screen displays a thumbnail grid with recording dates and file sizes.
Tap a thumbnail to play a video, or use its delete control to request deletion.
The gallery remains available without camera or microphone permission.
Back from review keeps the recording.
Retake and library deletion require confirmation.
Uninstalling the app removes its recordings.

The app stops recording when it leaves the foreground.
Finalization continues after the screen or Activity is destroyed.
A durable engine session starts before CameraX, records live observations and lifecycle events, and keeps its UUID through review and recovery.
Provisional recognition and vision timestamps remain separate from finalized media time, so they cannot accidentally drive edits.
The library checks pending recordings from interrupted sessions, retaining valid videos and removing invalid partial outputs.
Interrupted files whose format cannot be determined stay visible in the library so you can try playback or delete them.
An active recording is protected from recovery by a locked pending marker.
A process killed before a valid video has been written cannot always yield a recoverable recording.

## Capture-time pause cuts

Long interior non-speech pauses are marked while recording, including when captions are disabled.
A bundled 885 KB Silero voice-activity model detects speech and pauses without a caption-model download or network connection.
It uses the existing native CPU runtime; caption recognition is unchanged.
If the neural detector is unavailable, the app falls back to WebRTC VAD, then conservative near-silence rules.
The camera shows a potential-pause count after a candidate has been saved to the engine.
After Stop, the app aligns microphone timing and confirms candidates against the saved video audio before creating reversible cuts.
Unreliable alignment falls back to saved-audio analysis.
No Whisper model is needed for pause detection.

Open Edits in review to undo or reapply individual cuts, or restore all of them.
Playback and Save edited copy use the same edit plan, and the original stays untouched.
The detector can still miss pauses around background voices, music, or speech-like noise.
It does not identify the intended speaker, and review remains necessary.
See the [pause decision contract](docs/live-pause-decisions.md) for timing and safety rules.

## Downloadable offline captions

Open the camera menu, then Feature marketplace, and download Offline Captions.
The first download needs internet and approximately 78 MB of storage for the multilingual Whisper tiny model.
The app verifies its pinned SHA-256 checksum before enabling it.
The model comes from the public `ggerganov/whisper.cpp` repository on Hugging Face.
Only model data is downloaded; the inference runtime is bundled in the APK.

With Auto-caption new recordings enabled, the caption engine starts alongside recording.
It keeps one model session open and processes overlapping audio windows, displaying the latest committed caption on the camera screen.
After Stop, it finishes the remaining words, opens captioned playback, and automatically renders a separate captioned MP4.
You can correct text with Edit captions and choose Save captioned copy again after making changes.
The original recording is preserved.

CameraX retains its original audio/video pipeline.
A separate live microphone feed is checked against the actual recorded audio before its captions are accepted.
If live capture, inference, or alignment fails, the app generates captions from the saved recording instead.
That fallback takes longer.
Caption processing and export survive Activity recreation after the recording has finalized.
Destroying the Activity during an unfinished recording stops live inference; the original remains recoverable through the normal recording flow.
Existing recordings also have a Generate captions action.
Removing the model frees its storage without deleting videos or saved caption text.

Whisper, Silero, and face tracking currently execute on CPU.
The [iQOO 15 inference framework](docs/iqoo15-inference.md) prefers validated NPU adapters, reports CPU fallback explicitly, and provides strict NPU validation.
Compatible production NPU artifacts are not yet integrated; successful QNN runtime initialization alone does not accelerate these models.
Processing time and transcription accuracy depend on the phone, language, and recording quality.
Keep the app open while processing; jobs survive screen navigation but do not resume after process termination.
Videos longer than 120 seconds are currently rejected by the caption decoder.
The current milestone targets 30-second recordings.

## Build and local tests

See [current verification](docs/verification-current.md) for the latest build results and device-testing limits.

Run `./gradlew :engine:test` to verify the engine without Android or a connected phone.
The [engine foundation contract](docs/engine-foundation.md) describes ordering, persistence, migration, and current limits.
Script workflows and performance optimization are intentionally deferred.

Use Android Studio's configured Gradle JDK, or set `JAVA_HOME` to a suitable installed JDK in your terminal.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

## Device tests without uninstalling the app

Connect an unlocked test phone with USB debugging enabled.
The flow tests record short videos using its camera and microphone and delete only their test recordings.
Use a dedicated test device when possible.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w -r \
  -e class com.example.one_take.CameraLayoutTest,com.example.one_take.CameraRecorderStateTest,com.example.one_take.RecorderControlsTest,com.example.one_take.RecorderFlowTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

Do not use `connectedDebugAndroidTest` on an installation containing recordings you need to keep.
In this setup, Gradle's connected-test runner uninstalls the app during cleanup and removes private storage.
The direct instrumentation commands above preserve the app installation.
