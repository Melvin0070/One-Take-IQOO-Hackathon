# Current verification

## Atomic commit ba90938 recheck, 2026-09-12

On the user's subsequent request to test the commit, the focused check passed:
5/5 ProjectCatalog unit tests and 4/4 ProjectsFlowTest device tests (7.026 seconds).
The device run completed without foreground assistance on I2501 `10BFC41SMX001UZ`.
It verifies saved edits across reopening/recreation, original-file hash preservation,
confirmed deletion, unavailable details, and journal-derived script title/mode.

Commands: `./gradlew.bat :app:testDebugUnitTest --tests com.example.one_take.ProjectCatalogTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`,
then `adb install -r` for both APKs and direct instrumentation with `-e class com.example.one_take.ProjectsFlowTest`.
Build and focused tests passed; the full suite was not rerun. Logs: `app/build/projects-atomic-check.log`
and `app/build/projects-atomic-device.log`. No implementation changes were needed.

## Projects screen #63, 2026-09-12 (Windows)

Base: `9b7f909`, branch `feat/63-projects-screen`. See [scope and remaining integration](projects-screen.md).
The existing README edits and untracked main-validation/assigned-plan documents were preserved.
Host: Windows with Android Studio JBR; device: ADB-discovered iQOO I2501, serial `10BFC41SMX001UZ`.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:testDebugUnitTest :engine:test :engine-android:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --continue --console=plain
```

Results: engine 100/100, app 141/143, engine-android 17/24. All five new ProjectCatalog tests pass.
The full command exits 1 due to nine failures reproduced before feature implementation:
two VideoStoreTest pending-marker deletion assertions on Windows and seven VisualAnalyzerTest native-library loading errors.
No implementation in those areas was changed. Both APKs and instrumentation compilation succeed;
app lint has zero errors, 29 warnings and two hints. Logs are under ignored `app/build/projects-*.log`.

Test-first evidence: all five catalog tests failed against the empty catalog contract before implementation;
the Projects deletion/navigation test failed against the old Library screen before its replacement.
The first updated device run passed seven of eight tests. Its script fixture incorrectly emitted a live event
after finalization; the engine rejected it. The fixture was corrected to request/start capture, emit progress,
then finalize. No engine validation was relaxed.

The four ProjectsFlowTest cases subsequently passed on the phone: persisted cut choices and undo survive
reopening/recreation without changing the original SHA-256; delete cancellation preserves the recording and
confirmation preserves another recording; unavailable media details remain visible/deletable; saved script
title/mode survive recreation. The four RecordingModeFlowTest cases also passed in the first device run.
Installed using `adb install -r` and direct instrumentation, without clearing app data or uninstalling.
The Projects screenshot was inspected at `app/build/projects-63.png`.

The broader 14-test run passed 12 cases in 256.865 seconds and required foreground assistance after
instrumentation paused in its helper Activity. Its recording-delete and retake cases failed. RecorderFlowTest
cleanup now disposes composition in the foreground, matching VisualSuggestionsTest; those two cases also
wait for the existing post-processing ownership guard before exercising deletion. An unassisted six-case
rerun passed all four Projects cases and retake; the recording-delete case lost its Compose hierarchy when
Vivo Remote Control became foreground. The final isolated recording-delete rerun passed in 6.242 seconds
(`projects-delete-final.log`). This is not a claim that the broad suite passed in one unattended invocation.
Testing paused when the user requested implementation-only work; the later authorized commit recheck is recorded above.

```powershell
adb -s 10BFC41SMX001UZ install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10BFC41SMX001UZ install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 10BFC41SMX001UZ shell am instrument --user 0 -w -r -e class 'com.example.one_take.ProjectsFlowTest,com.example.one_take.RecorderFlowTest#keptVideoAppearsInLibraryAndCanBeDeleted,com.example.one_take.RecorderFlowTest#retakeCanBeCancelledOrConfirmed' com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
adb -s 10BFC41SMX001UZ shell am instrument --user 0 -w -r -e class 'com.example.one_take.RecorderFlowTest#keptVideoAppearsInLibraryAndCanBeDeleted' com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

Full reordered-clip acceptance remains untested because #53/#54/#64 are not integrated. Existing cuts are
source-ordered intervals, not the new reorderable clip timeline. This is not full completion of #63.

## Assigned recording UI, 2026-09-12 (Windows)

Work began from freshly pulled `origin/main` at `974c489` after the requested tracked/untracked cleanup.
Implementation order and dependency boundaries are in [assigned recording UI](assigned-recording-ui.md).
Host: Windows, Android Studio JBR 25.0.3; connected phone: iQOO I2501, Android 16 / SDK 36, serial `10BFC41SMX001UZ`.
This is a different physical serial from the historical macOS validation below.

From PowerShell at the repository root:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :engine:test testDebugUnitTest --continue --console=plain
./gradlew.bat lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

Unit results: 91/91 engine, 17/17 engine-android, and 133/135 app tests passed (241/243 total).
The new word-count test passes.
The full unit command exits unsuccessfully because two unchanged `VideoStoreTest` cases fail on Windows:
`recoveryRetainsPlayableOutputAndRemovesInvalidPartial` (line 41) and
`undecidableInterruptedVideoRemainsVisibleAndCanBeDeleted` (line 108).
Both assert that a pending marker has been removed; the existing implementation attempts deletion while its channel remains open.
`git diff --quiet HEAD -- app/src/main/java/com/example/one_take/VideoStore.kt app/src/test/java/com/example/one_take/VideoStoreTest.kt` returns zero.
No recovery/locking behavior was changed to work around these failures.

Build and instrumentation APK assembly pass. Lint has zero errors, 29 warnings, and two hints;
none reference the new Home, script-entry, recording-setup, or overlay files.
Evidence logs remain under ignored `app/build/recording-ui-validation/`.

The app and test APKs were installed with `adb install -r`, preserving the existing installation.
Direct instrumentation completed with `OK (17 tests)` in 371.68 seconds:
four `RecordingModeFlowTest`, three `RecordingOverlayTest`, six `RecorderFlowTest`,
one `CameraLayoutTest`, and three `RecorderControlsTest` tests.
This covers clipboard paste, draft/accepted-script recreation, Home/Projects navigation,
the three-line transcript limit (including a long segment), mode separation, missing-model behavior,
real video capture/playback, background finalization, recovery, and confirmed retake/deletion.
Test cleanup removes only recordings created by the tests.
The run needed explicit `adb shell am start -n com.example.one_take/.MainActivity` foregrounding
when Vivo Remote Control became foreground; it is not an unattended background-execution result.
After the final pluralization, library-return, and theme-consistent text-measurement changes,
the APKs were rebuilt/reinstalled and all seven new mode/overlay tests passed again in 12.705 seconds,
without foreground assistance (`device-ui-final.log`).
Draft persistence was checked through Activity recreation and a fresh store instance;
a separately orchestrated OS process-kill test was not run.

```powershell
adb -s 10BFC41SMX001UZ install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10BFC41SMX001UZ install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 10BFC41SMX001UZ shell am instrument --user 0 -w -r -e class com.example.one_take.RecordingModeFlowTest,com.example.one_take.RecordingOverlayTest,com.example.one_take.RecorderFlowTest,com.example.one_take.CameraLayoutTest,com.example.one_take.RecorderControlsTest com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

The new mode-aware engine session header (#45/#57), matcher-driven teleprompter (#48/#58),
and replacement Projects/editor flow remain separate open work.
The transcript overlay uses the existing caption pipeline and was tested with injected segments;
fresh model download and live ASR accuracy are not part of this UI validation.

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
