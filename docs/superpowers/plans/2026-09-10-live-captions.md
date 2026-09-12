# Live Captions Implementation Plan

**Goal:** Perform transcription during a 30-second recording and automatically save the captioned result after Stop.
**Architecture:** Keep a Whisper context alive during the take and process overlapping PCM windows serially.
Camera recording remains authoritative and must survive caption failures.
Validate the live microphone path against the recorded audio; retain post-record transcription as a fallback.
**Tech stack:** Kotlin, CameraX 1.6.2, AudioRecord, whisper.cpp 1.9.2, Media3 Transformer.
**Spec:** User-approved proposal in the conversation on 2026-09-10.

## Constraints

Preserve original recordings and downloaded model installation.
Never uninstall the app or run connectedDebugAndroidTest.
Use reinstall-in-place and direct instrumentation on the phone.
Do not claim instant export or NPU execution.

## Tasks

- [x] Add a persistent native model session with serialized PCM inference and cancellation; retain offline file transcription.
- [x] Capture live mono PCM during a take with bounded memory, explicit stop/release, and detection of unusable audio.
- [x] Implement overlapping windows, conservative text deduplication, bounded backlog, final-tail processing, and timestamp reconciliation against the saved audio.
- [x] Wire recording start/stop/finalization to live sessions; show caption progress during capture and automatically export after finalization.
- [x] Add meaningful window-merging tests and real-device concurrent recording/inference verification.
- [x] Build, lint, test offline fallback and 30-second capture, measure finalization latency, and review changes.

## Acceptance

A downloaded model is reused offline.
A 30-second take performs inference before Stop, retains playable audio/video, and produces timed captions plus a separate captioned MP4 automatically.
If live audio is unavailable or inference fails, the recorded file remains intact and the existing offline path is used.
Test results report actual latency and validation gaps.

## Implementation notes

Live capture is validated alongside CameraX on the connected Samsung phone.
A separate mono AudioRecord starts before CameraX, and recorded-file audio determines whether the live result is accepted.
There is no use of restricted CameraX audio internals.
Native contexts persist across windows; short live windows use a 15-second encoder context with padding, while file transcription retains the default context.
Final audio decoding overlaps final live inference.
Export remains a hardware decode/render/encode pass and is not instant at 1080p.
