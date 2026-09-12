# Verification

The initial review Back behavior was reproduced on the connected Samsung SM-A528B running Android 14.
Back deleted the newly recorded MP4, and the original record control had no accessibility label.
The first saved-library instrumentation test failed because no Saved videos node existed.

The implementation was checked with `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`.
The local suite contains 10 passing tests covering elapsed time, finalized storage, recovery, active-file protection, legacy files, and deletion ownership.
Lint reports 0 errors and 10 warnings about dependency versions and the existing portrait lock.

Nine device tests passed through direct instrumentation.
They cover synchronous Starting state, rejection of duplicate starts, immediate stop, disabled controls, permission UI, recording and playback, library navigation, confirmed deletion, Activity recreation, and foreground interruption during recording.
Playback checks inspect video duration and dimensions and verify that the actual VideoView plays.
A tenth device test reproduced a late library-refresh failure, then passed after the file-availability notification was added.
The final build and all 10 local tests passed again after the remaining permission and test-cleanup changes.
A manual check on the final APK confirmed that revoking camera access displays the permission gate, Saved videos remains accessible while camera permission is denied, and denying the Android permission dialog displays the Settings path.
Camera and microphone permissions were restored, and the final app was left open with recording enabled.

## Reference camera UI verification

The redesigned camera was built with `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
All 13 local tests pass, and lint reports 0 errors and 10 dependency/orientation warnings.
Direct instrumentation completed with `OK (11 tests)` in 306.275 seconds on the Samsung SM-A528B.
This includes recording, playback, library recovery and deletion, capture-state guards, zoom clamping, supported quality state, accessibility controls, and grid persistence after Activity recreation.
The final sensor-accuracy handling was rebuilt and manually checked after that suite.
Sensor accuracy transitions and two-finger pinch gestures have not been simulated on the device.

Manual checks confirmed 1080p to 720p switching, camera flip, slider zoom to 3.6×, and return to 1080p at 1×.
A final short recording opened in review, appeared in the library, and replaced the gallery placeholder with a thumbnail on returning to the camera.
That manually created test recording was then deleted through its confirmation dialog.
The final app was left on the camera screen.
The camera screen holds the screen-awake flag while composed and restores the previous flag when disposed.

## Gallery UI refresh

The thumbnail-grid gallery passes `assembleDebug assembleDebugAndroidTest lintDebug` with 0 lint errors and the same 10 dependency/orientation warnings.
The existing `recordingSurvivesLifecycleRecreationBackAndPlayback` device test passed through direct instrumentation in 51.999 seconds.
It records a real video, preserves it across Activity recreation, opens it from the gallery, and verifies actual VideoView playback.
Manual checks covered populated and empty layouts, thumbnail navigation, canceling deletion, and confirming deletion of only the manually created test clip.
Screenshots were inspected on the Samsung SM-A528B.

## Test-runner incident

The first `connectedDebugAndroidTest` invocation uninstalled the debug application during cleanup.
Its private storage contained one pre-existing recording, which was removed with the installation.
This was disclosed during the task.
Subsequent runs use `adb install -r` and direct `am instrument` so the target app stays installed.
Device test cleanup deletes only recordings created by the test itself.

## Limits

A process killed before CameraX writes a valid video may leave an unplayable partial recording.
Recovery retains undecidable files for user review or deletion and protects actively written files.
Testing was performed on one Android 14 phone, not across all supported Android versions and camera hardware.

## Downloadable offline captions

The final caption build passed `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`.
All 21 local tests passed; lint reported 0 errors and 20 warnings.
The debug APK was installed with `adb install -r`, preserving app data.

The model was downloaded through the marketplace on the Samsung SM-A528B and verified against its pinned size and SHA-256.
The first device run exposed a detect-language-only setting in Whisper; switching to automatic language detection with transcription enabled fixed the empty result.
The next run exposed Media3 rejecting an empty text bitmap during caption gaps; a transparent non-empty overlay fixes those frames.

Direct `CaptionMilestoneTest` instrumentation passed with `OK (1 test)` in 17.201 seconds while airplane mode was enabled and Wi-Fi/mobile data were disabled.
The fixture was a 30-second 480x854 MP4 with synthetic English speech.
Transcription took 12,613 ms; export took 4,089 ms.
The test verified recognizable speech, bounded timestamps, caption persistence, retained audio, output duration, a decodable frame, and an unchanged SHA-256 for the original recording.
An exported frame was visually inspected and showed burned-in caption text.
These timings are for this small test fixture, not a 1080p performance guarantee.
Whisper tiny made recognition errors, so text correction is part of the review flow.
Hindi/Hinglish accuracy and sustained thermal performance have not been benchmarked.

The separate code review verified startup cleanup of abandoned export files, source-deletion metadata cleanup, and the auto-caption switch wording.
Force-stop recovery during export has not yet been tested on the device.

Manual device checks confirmed Generate captions from the gallery, editing a recognized sentence, saving changes, exporting, and playing the burned-in copy.
Marketplace removal, immediate download cancellation, partial-file cleanup, and a fresh successful download were also exercised.
Only synthetic test videos were created and deleted during these caption checks.


## Captions during recording

The live implementation starts a persistent local Whisper session and a parallel microphone stream when recording starts.
It transcribes overlapping audio windows while CameraX saves the original, verifies microphone timing against the finalized MP4 audio, then automatically exports a separate captioned copy.
An unavailable or mismatched live stream falls back to transcription of the recorded file.
Inference uses the CPU, not the NPU.

The build passed `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` with 33 tests, 0 lint errors, and 20 warnings before the final device run.
A subsequent comment clarification and additional pending-hypothesis regression test bring the unit suite to 34 tests.
The persistent-engine benchmark and the existing offline-caption milestone also passed direct instrumentation.

The final `LiveCaptionRecordingTest` passed with `OK (1 test)` in 66.340 seconds on the Samsung SM-A528B with airplane mode enabled and Wi-Fi/mobile data disabled.
It recorded 30 seconds of speaker-played synthetic English speech, completed 8 transcription windows before Stop, and used those live captions without fallback.
Captions were ready 14,485 ms after Stop; the burned-in MP4 was ready 32,987 ms after Stop.
A preceding successful run measured 7,066 ms and 25,654 ms respectively, so immediate completion is not achieved and timing varies.
Both original and exported videos retained audio and 863 frames at 1920x1080 in the final run.
The test recreated the Activity during export and verified successful completion.
An exported frame was visually inspected and showed readable burned-in text.

Whisper tiny still makes recognition errors, repeats some revised phrases, and can hallucinate text in noisy or quiet sections.
Hindi/Hinglish accuracy, thermal endurance, and parallel microphone compatibility on other phones remain unverified.
The test preserves existing recordings and deletes only its own generated gallery entries.
The installed app and downloaded model were retained, networking was restored, and the phone was left on the camera screen.


## Engine foundation

The engine foundation is implemented in the pure Kotlin JVM `:engine` module.
The current Android recording and editing flow uses adapters and a checksummed event journal.
Script features and speed optimization remain deferred.

Final verification command: `./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
The engine suite passed 11 tests and the app suite passed 93 tests, with no failures or skipped tests.
Lint reported 0 errors, 30 warnings, and 2 hints.
The engine production sources contain no Android imports.

Direct instrumentation of `EditFeatureTest` and `LiveCaptionRecordingTest` passed `OK (3 tests)` in 72.788 seconds on Samsung SM-A528B, Android 14.
Airplane mode was enabled and Wi-Fi disabled during the run.
Installation used `adb install -r`, preserving the installed application and downloaded model.
The two editing tests covered imported metadata, actual clipped playback, Activity recreation, undo/reapply persistence, captioned and caption-free export, retained audio, a scoped sharing URI, and unchanged original-file hashes.
The live test covered 30 seconds of real CameraX/microphone capture, recognition before Stop, successful live-audio reconciliation, automatic caption/cut persistence, and export through the engine-backed flow.
The output frame at edited time six seconds was visually inspected and displayed “After the cut”, confirming caption remapping across the removed interval.
Test cleanup removed only identified test recordings and their metadata.

The initial editing tests failed because their synthetic sidecar assumed exactly 30 seconds instead of reading the MP4 duration.
The fixture now uses the actual duration; a separate migration test covers small rounding differences in legacy metadata.
An earlier live run used the existing offline fallback because recognition could not keep up; the final run used live captions successfully.
No speed or thermal guarantee is inferred from these runs.

JVM tests exercise replay ordering, late captions, failed event sinks, immutable snapshots, terminal states, invalid timing, metadata replacement, missing-media persistence, rounded legacy migration, interrupted journal tails, completed-record corruption, and competing journal writers.
API 24/25 runtime behavior, power-loss recovery on hardware, and iQOO 15 behavior remain unverified.
The app uses API-24-compatible journal APIs and retains its existing minimum SDK.
The durable ledger starts after MP4 finalization; in-flight recording recovery still belongs to the existing VideoStore path.

## Live engine connection

The capture lifecycle now enters a durable UUID journal before CameraX starts.
Recording, stop, cancellation, failure, interruption, provisional transcript, and vision events replay through the pure engine.
Finalized project adoption retains the UUID and any imported caption/edit history.
MEDIA, RECOGNIZER, and CAPTURE_ESTIMATE positions remain separate; live observations cannot create finalized captions or cuts.

Verification command: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
The engine suite passed 21 tests and the app suite passed 107 tests, with no failures or skipped tests.
Lint reported 0 errors, 31 warnings, and 2 hints.
The added StaticFieldLeak warning concerns the application-scoped coordinator retaining applicationContext, not an Activity context.

Direct instrumentation of `CameraRecorderStateTest`, `LiveEngineLifecycleTest`, `EditFeatureTest`, and `LiveCaptionRecordingTest` passed `OK (9 tests)` in 320.497 seconds on Samsung SM-A528B, Android 14, in airplane mode with Wi-Fi and mobile data disabled.
The run covered normal capture, rapid stop, background stop, Activity recreation, provisional event domains, invalid-media retention and retry, same-UUID project adoption, editing, and captioned/caption-free export.
Real microphone recognition produced eight windows before Stop and entered the RECOGNIZER journal domain.
Live reconciliation succeeded without fallback; captions became ready 4,681 ms after Stop and export completed 20,870 ms after Stop.
These are observed results for one 30-second test recording, not an export-speed or thermal guarantee.

An initial normal-capture test stopped four milliseconds after CameraX Start and received ERROR_NO_VALID_DATA, correctly producing a terminal failed session.
That normal-success test now records for one second, while the separate rapid-stop test accepts either valid media or the documented zero-frame failure.
An initial vision assertion expected one injected observation to survive coalescing against real camera updates; it now checks accepted vision events and their clock domain.

The implementation retains the existing limitation that Activity recreation during an unfinished take stops live transcription; the raw media and capture history remain recoverable.
It does not restart recognition or export after process termination.
Scripts, replacement models, NPU integration, speed optimization, iQOO 15 validation, and thermal endurance remain deferred.

The final recovery checks passed `OK (3 tests)` in 95.461 seconds after the review fixes.
They cover cancellation before CameraX starts, an exception while reading a corrupt capture journal during finalization, and retry after a guarded terminal-event failure.
Only each test's own journal was corrupted and restored.
Recovery preserved the session UUID and raw-media hash in both journal-error cases.

A staged process-death probe started a real camera recording, durably recorded its session, and was force-stopped from the host.
The prepare stage therefore ended with the expected instrumentation process-crashed result; it is not counted as a passing test.
The restarted verify stage passed `OK (1 test)` in 48.036 seconds.
Its history contained `CaptureInterrupted("process_restarted")`, and the actual recording recovered as playable media in READY with the same capture/project UUID.
A valid MP4 survived this particular termination; that does not guarantee footage can be recovered after every kill or power loss.
Across the final runs, 13 device tests passed.

Separate review found no remaining blocking runtime or data-integrity issues after the finalization and orphan-session recovery fixes.
The coordinator's `flush()` is a dispatcher barrier, not a guarantee that subsequently queued/coalesced vision updates have drained.
All six pre-existing recordings retained exactly the same SHA-256 hashes after testing.
Installation used `adb install -r`; no uninstall or app-data reset was performed.
The downloaded model and enabled setting were retained, and airplane mode, Wi-Fi, and mobile data were restored to their original states.

## Capture-time pause decisions

The microphone now feeds a streaming near-silence detector independently of caption recognition.
Candidates remain in the RECOGNIZER domain until saved-audio alignment and silence confirmation produce reversible MEDIA cuts.
The original recording is preserved, and review and export consume the same persisted plan.

The initial full verification run passed 28 engine tests and 119 app tests, with zero failures, errors, or skipped tests.
Debug app and instrumentation APK builds and lint succeeded.
Direct Samsung SM-A528B instrumentation passed the two LivePauseFeatureTest cases and the live-caption recording/export regression in 169.269 seconds.
A separate run passed both EditFeatureTest cases plus background-stop and Activity-recreation checks in 120.577 seconds.
All tests ran offline using replacement installation without clearing app data.

The actual room recording with captions disabled produced zero candidates, zero cuts, and zero recognition windows.
The decoded synthetic AAC fixture produced a durable candidate and confirmed cut, then passed UI Undo/Apply and edited-copy export with an unchanged original hash.
This proves the pipeline with controlled audio, not reliable pause detection in ambient noise.
The strict near-silence threshold remains a known sensitivity limitation.

An earlier edit regression attempt crashed inside Compose SlotWriter during Activity disposal.
The individual test and pair subsequently passed unchanged.
Test cleanup now unmounts playback before deleting its source media, and the pair passed again.
That improves teardown hygiene but does not establish the intermittent framework crash's root cause.
The test-owned synthetic recording left by that crash was identified by its exact asset hash and removed with only its own sidecars.
All six pre-existing recording hashes matched the baseline after these runs.

Review identified a final-read ordering race before completion of this milestone.
The final implementation captures a per-take microphone barrier, stops and awaits that microphone, and durably drains its complete candidate snapshot before SourceFinalized.
Live callbacks also submit full snapshots, retaining capture-time journaling and recovering startup candidates without duplicates or fabricated CaptureStarted events.
A delayed old recorder barrier cannot stop a newer microphone session.
The final full build again passed 147 JVM tests, both APK builds, and lint with zero errors, 31 warnings, and 2 hints.
Separate review found no remaining material issue in the corrected path.
The exact delayed-old-finalize plus new-recording interleaving is covered by ownership in code review, not a dedicated device race injection.

One post-fix device attempt could not resume its test Activity after the phone entered Dozing with the lockscreen visible.
That setup-failed run was stopped before any recording fixture was created.
The phone was awakened and temporarily configured to remain awake while plugged in before rerunning.

The final corrected APK passed all five rerun device cases in 270.202 seconds: background stop, Activity recreation, captions-disabled real capture, delayed pause-candidate drain with review Undo/Apply/export, and live captions with automatic export.
Together with the two earlier edit-export cases, seven distinct device cases passed for this milestone.
The six original recording hashes still matched after the final run.
The downloaded model remains installed and enabled.
Airplane mode, Wi-Fi, mobile data, and the plugged-in screen-awake setting were restored to their original values, and the app was left on its ready capture screen.
No iQOO speed, NPU, thermal endurance, or noisy-room pause-recall target is claimed.

## Noisy-room pause detection, 2026-09-12

The initial steady-noise fixture reproduced a missing cut in the old RMS detector on Samsung SM-A528B.
WebRTC VAD fixed the controlled fixture but classified the entire actual room capture as speech in modes 0 through 3.
The user confirmed ordinary room noise with no TV, music, or other speaker.
A test-owned PCM sample showed a strong low-frequency band, particularly around 234–250 Hz.
Aggressive high-pass filtering was investigated only in temporary host scripts and was not shipped.

The final app uses bundled Silero v6.2.0 through the existing whisper.cpp runtime for voice activity, with fresh independent state for live microphone and saved audio.
Caption transcription remains unchanged.
The model is 885,098 bytes and its SHA-256 is verified before native loading.
The classifier consumes complete 512-sample frames, uses one CPU thread, and requires probability >=0.2 for speech or <=0.15 for non-speech.
Unknown or invalid frames cannot extend a pending pause.
WebRTC mode 0 and near-silence detection remain availability fallbacks.

The first neural fixture assertions expected near-maximal pause removal based on the temporary ONNX probe.
Native GGML retained more uncertain audio while finding valid cuts, so the assertions now require at least 800 ms strictly within the known pause spans, consistent with the >1.2 second pause rule and 200 ms padding at each end.
The initial 0.10 non-speech threshold detected live candidates but lost every confirmation on a second encoded recording.
A diagnostic replay found two microphone candidates, no saved-audio candidates, and a valid -90 ms alignment.
Changing only the non-speech threshold to 0.15 retained a confirmed saved-audio pause while the soft-speech and noise-boundary tests passed.
Both captured-audio cases are retained as regression assets; temporary reflection and raw-PCM test hooks were removed.

The final full Gradle run passed 37 engine tests and 119 app JVM tests, with no failures, errors, or skips.
Both debug APK builds and lint passed; lint has 0 errors, 31 warnings, and 2 hints.
The positive real camera/microphone test produced 2 durably journaled candidates and 1 confirmed live cut with captions disabled and zero recognition windows.
The stronger test requires both a live candidate and a confirmed cut.

A ten-second native trace of the final app during export validation loaded in the local official Perfetto UI with CPU scheduling, CPU frequency, and app process tracks and no console/import error.
Evidence is under `tools/device-monitor/runs/silero-validation-20260912.pftrace` and the corresponding PNG.
This is trace validation, not a speed or thermal improvement claim.

The initial 14-case device suite passed 13 cases; its review/export failure came from a legacy tone-only speech fixture.
That test now feeds the production speech detector with the speech/noise fixture and passed its complete Undo/Apply/export rerun.
Native startup review found that unchecked warmup could suppress fallback after a graph failure.
Creation now requires a second NaN-sentinel warmup to produce a finite probability in range before returning a handle.
The targeted native fix passed review and nine classifier/noise device cases, but graph failure itself has not been fault-injected.
A repeated physical capture found two live candidates and no confirmed cuts; the next capture on the same binary found two candidates and one confirmed cut.
This intermittent result prompted a separate examination of uncertain pause boundaries rather than removal of saved-audio confirmation.

Classifier UNKNOWN now freezes one already-qualified quiet interval until the next speech anchor.
The longest separate interval wins, with later ties preferred; uncertain frames are never bridged or cut, and invalid input discards retained state.
Eight added engine cases cover the retained interval, split short runs, missing right anchor, longer-run selection, invalid/error reset, and chunk invariance.
The updated full build passed 45 engine and 119 app JVM tests, both debug APK builds, and lint.
A fresh 10-second trace from this build during export testing is `tools/device-monitor/runs/silero-boundary-validation-20260912.pftrace` (33,465,581 bytes).
The local official Perfetto UI displayed CPU 0–7 scheduling/frequency and app process 14307, with no console error; the matching PNG records the loaded view.
Separate review found no material issue in the final interval-retention implementation and its regression tests.

The final 14-case device run passed 13 cases in 199.561 seconds.
Real capture produced two live candidates and two confirmed cuts, and review Undo/Apply/export plus all noise/classifier cases passed.
The caption case failed its live-path assertion because transcription exceeded the existing backlog limit, not because the job failed.
Its diagnostic recorded two completed live windows, the explicit “could not keep up” fallback, `error=null`, and a completed captioned export from saved audio.
This throughput limitation is outside the requested feature-first milestone and remains for later optimization.
A second real pause capture on the same final binary also passed: two candidates yielded three confirmed intersections, without widening either live or saved intervals.

The final targeted repeat passed both real pause capture and live caption/automatic export in 118.954 seconds.
All 14 distinct device cases therefore passed on the final binary across runs; the full-suite caption throughput fallback remains documented above and is not hidden by the successful repeat.
The six original recording SHA-256 hashes remained unchanged, and the downloaded caption model remained installed and enabled.
Airplane mode, Wi-Fi, mobile data, and the plugged-in screen-awake setting were restored to their original values.
