# experiments/ — the pre-event prototype

**Not in the Gradle build.** Deliberately excluded from `settings.gradle.kts`. Nothing here
compiles, ships, or is maintained.

This was an experiment written before the design was settled. It is kept because parts of it
are working, on-device-proven code, and re-deriving them costs hours. **Mine it freely; do
not extend it.**

## Why it is not in the build

It ships a second ML runtime stack — MediaPipe `tasks-vision` plus a vendored `whisper.cpp`
— which both the one-NPU-runtime rule and `config/allowed-dependencies.txt` reject. Keeping
it in the build would make the guards unusable, and the guards are what stop many agents
from quietly breaking the permission and dependency promises.

It also drags a CMake/NDK native build into every compile, and `recorder-app` is a
different product with a different package name.

## What is worth taking

| Want | Look at |
|---|---|
| CameraX recording, quality selection, state machine | `recorder-app/src/main/java/com/example/one_take/CameraRecorder.kt`, `CaptureState.kt` |
| Silero VAD on-device, and the WebRTC fallback | `.../audio/SileroSpeechClassifier.kt`, `WebRtcSpeechClassifier.kt`, `SpeechPauseDetector.kt` |
| JNI / CMake setup for a vendored native speech lib | `recorder-app/src/main/cpp/` |
| QNN/HTP graph runtime, Whisper bundle install and transcription reference | `recorder-engine-android/` |
| Media3 playback from an edit plan | `.../editing/EditedVideoPlayer.kt` |
| Transformer export with overlays | `.../captions/CaptionExporter.kt` |
| Live mic feed alongside CameraX — **the concurrent-capture question, already attempted** | `.../captions/LiveMicrophone.kt`, `LiveCaptionSession.kt` |
| Append-only event journal with checksums | `.../engine/FileEventJournal.kt`, `EngineEventCodec.kt` |
| Edit plan / reversible cuts | `recorder-engine/src/main/kotlin/com/onetake/engine/` |
| Face tracking through MediaPipe | `.../vision/FaceTracker.kt` — **note: not the NPU path we are building** |

The archived `recorder-engine-android/` CMake files and Android sources are retained as a
device-proven QNN reference, but the module is excluded from the root build and settings.

The combined prototype was tested at commit `5cce55d1d0cc84952bfb9b11eaef79ec472075c3`.
To reproduce the historical Android build commands in `docs/qualcomm-whisper-*.md`, create a separate checkout of that commit:

```sh
git worktree add --detach ../OneTake-prototype-check 5cce55d1d0cc84952bfb9b11eaef79ec472075c3
```

Run those commands inside that checkout with its documented SDK and model prerequisites.
The current root `./gradlew verify` validates the nine-module scaffold, not these archived Android modules.

`tools/device-monitor/` is separate and still live — an ADB-based profiler that hosts the
real Perfetto UI locally. See its own README; it needs no app changes.

## If you take code from here

Rewrite it against the current contract rather than copying it. The sample-index clock, the
port interfaces and the ledger event shapes are all different, and a half-ported file is
worse than a fresh one. Take the **pattern** — the CameraX lifecycle handling, the JNI
glue, the Transformer overlay setup — not the types.
