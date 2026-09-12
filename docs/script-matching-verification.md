# Script matcher and teleprompter handoff — 2026-09-12

Implements #48 and #58, rebased onto main at `549e94b` (#70: Home, mode selection, script entry, and `RecordingOverlay`).

## Integration decisions

The latest issue comments and milestone descriptions were fetched with `gh` on September 12. They explicitly defer #57 and #49 and feed live caption segments directly to the matcher for the demo. #45 is not implemented; #56 landed with #70.

- `engine/ScriptMatcher.kt` establishes the pure Kotlin `ScriptMatcher`, `ScriptProgress`, `TakeAttempt`, and minimal `RecordingSession` event-sink interface. This is a compatibility boundary, not the complete #45 session header/reader implementation.
- `Change.ScriptProgressObserved` and `Change.TakeAttemptObserved` use the existing engine reducer, codec, and append-only journal. Recognition uses `RECOGNIZER`; initial/manual navigation uses `CAPTURE_ESTIMATE`. No provisional timing is promoted to media time. Finalization preserves script evidence.
- `CaptionJobs.startLive` forwards committed `LiveCaptionSession` snapshots to `ScriptCaptureController`; stable segment intervals prevent duplicate delivery from becoming repeated takes. Script captures drain final recognition before `SourceFinalized`. This can increase Stop-to-review latency by the remaining live inference time. Assisted captures keep their existing finalization behavior.
- The script comes from #56's `RecordingSetupStore` through `RecorderContent` navigation state. `RecorderContent` builds a `ScriptCaptureController` only in Script Mode, rebuilds it when mode or script changes, and starts a fresh one per take.
- `RecordingOverlay` owns the top-of-preview slot. Its Script Mode branch renders `TeleprompterOverlay` from `ScriptProgress`, replacing #70's manually scrollable script; Assisted Mode keeps the live transcript. `CameraScreen` passes progress and next/previous callbacks through and contains no matching logic.
- Auto-advance requires the downloaded Offline Captions model to be enabled. Without it the real recorder and manual navigation still work, and the overlay explains this limitation. No mock replaces live recognition.

## Matcher semantics

English normalization expands common contractions, removes punctuation/case differences, allows small synonyms and stop words, and tolerates one edit for words of at least five characters. Coverage is matched meaningful script tokens divided by meaningful script tokens; it is a heuristic, not semantic entailment.

Sentence/clause boundaries are preferred, short clauses within a line are grouped where possible, and long clauses are balanced toward 8–15 words. Explicit newlines remain boundaries. IDs use a text digest and duplicate occurrence index. Identical unchanged chunks retain IDs; editing their text produces a new ID.

Coverage threshold defaults to `0.6`; look-ahead includes three following chunks. A stronger qualifying later match skips intervening pending chunks. Partial lexical evidence below threshold is `MISMATCHED`; unrelated speech stays `PENDING`. Committed segments can accumulate partial coverage and can cover multiple chunks. Repeated covered chunks emit `TakeAttempt` hints without rewinding the current cursor. Manual previous retains existing coverage evidence; manual next skips an uncovered current chunk. Completion is represented by `currentIndex == chunks.size`.

## Verified locally

Host: macOS, Android Studio bundled JDK.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :engine:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Passed after the rebase onto `549e94b`: 100 engine tests, 138 app JVM tests, 17 engine-android JVM tests; no failures or skips. Android lint, debug APK, and instrumentation APK builds passed. Existing deprecation/nullability warnings remain.

The warmed 200-chunk test checked every measured segment against 5 ms across three passes. Observed averages were 0.1091, 0.0516, and 0.0285 ms; maxima were 2.8738, 0.2725, and 0.7355 ms. These are in-process matcher timings excluding Android inference, journal I/O, and cold JVM class loading/JIT. A cold run during concurrent compilation exceeded 5 ms; no cold-start or end-to-end latency guarantee is claimed.

Unit coverage includes the exact requested paraphrase, skip-ahead bound, repeated attempts and duplicate delivery, off-script pending behavior, partial accumulation, typo/synonym tolerance, multiple chunks per segment, manual navigation clocks, stable chunk IDs, all coverage states, immutable snapshots, journal round-trip, and replay after finalization.

## Verified on iQOO 15

ADB reported model `I2501`, manufacturer vivo, Android 16. Both APKs were installed with `adb install -r`; no uninstall or app-data clearing was used.

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument --user 0 -w -r \
  -e class com.example.one_take.TeleprompterOverlayTest,com.example.one_take.RecordingOverlayTest,com.example.one_take.RecordingModeFlowTest,com.example.one_take.ScriptRecordingTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

Result after the rebase onto `549e94b`: **OK (9 tests)**, 221.949 seconds. The earlier pre-rebase run covered only the first and last classes: OK (2 tests), 58.631 seconds.

- Injected fake matcher state moves the highlighted line; tapping the next line emits a manual skip, marks the previous line skipped, and previous navigation emits its own event.
- `RecordingOverlay` keeps the top slot exclusive: Script Mode renders the teleprompter, not the transcript; #70's mode flow still retains the pasted script across recreation.
- Real camera recording with a ten-line script entered through Home → Script Mode → Continue: manual skip updates the overlay, persists in the existing journal, survives finalization, and leaves a nonempty original video. This test temporarily disables automatic captions and restores the setting; it does not verify spoken auto-advance. It cleans only its owned recording and metadata.

The notes below describe the pre-rebase run. The first overlay run exposed duplicate semantics test tags; those were fixed and the focused suite passed afterward. Recorder regression attempts passed CameraLayoutTest, CameraRecorderStateTest, RecorderControlsTest, and two RecorderFlowTest cases before the run was interrupted after recurring Activity handoff stalls. The device alternated between the chat and AndroidX's EmptyActivity; explicit foregrounding with `adb shell am start -n com.example.one_take/.MainActivity` unblocked transitions, including the focused recording test. These runs are not a complete clean regression pass. LiveEngineLifecycleTest and the explicit two-stage process-death probe remain unverified in this change.

## Teammate checklist before merge

1. Run the focused suite above with the phone unlocked and One-Take in the foreground. Replace SERIAL with the currently discovered iQOO serial.
2. Run the README's recorder suite and LiveEngineLifecycleTest. Run LiveEngineProcessDeathTest using its documented prepare/force-stop/verify protocol; its ordinary invocation skips the probe.
3. Enable downloaded Offline Captions in Feature marketplace. Choose Script Mode on Home, paste the script below, and continue. Hold the phone at arm's length and read it aloud, omitting line 4. Confirm fuzzy auto-advance, line 4 skipped when line 5 is spoken, completion, and readable current/next lines. Also tap next and previous once, repeat a covered line, and verify recording stops and opens review. Confirm no journal error appears and the original remains playable.
4. Check layout in the demo's actual camera orientation and font size. The scripted test does not replace this visual/speech rehearsal.
5. Reconcile the #45 session contract if it lands before merge.

```text
Today I am going to show you how One Take works.
First we open the camera and choose our recording quality.
The script appears above the preview so we can follow along.
This sentence is intentionally omitted during our live demonstration today.
Next we speak naturally while the highlighted script advances automatically.
Small wording differences should still count toward the current sentence.
We can tap the next line to skip forward manually.
The previous button lets us return without erasing earlier coverage.
After recording we review the video and keep the original.
Finally we save our finished demonstration in the local library.
```

#48's requested JVM criteria are satisfied for the documented workload. #58's fake-matcher instrumentation and real manual-navigation recording checks pass; its human ten-line spoken acceptance check remains outstanding. The PR should remain draft until that rehearsal and the remaining regression checks are reviewed.
