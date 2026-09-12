# Live engine connection

This milestone connects existing capture and recognition features to the engine.
Scripts, model changes, and performance optimization remain deferred.

## Lifecycle

A UUID identifies a capture session before its media exists.
A capture-requested event is durably written before CameraX starts.
Camera callbacks append started, stop-requested, failure, cancellation, and finalized events.
The session survives Activity disposal through an application-scoped coordinator.
The original video remains authoritative and is never deleted because journal persistence fails.

## Clock domains

All positions use 16 kHz sample units, with explicit domains.
MEDIA is the existing finalized-MP4-relative timeline.
RECOGNIZER is the provisional microphone timeline before reconciliation.
CAPTURE_ESTIMATE converts monotonic elapsed capture time into sample units for lifecycle and vision observations.
Estimated positions are not hardware audio timestamps and must never drive edits.
Final captions retain the existing reconciliation against decoded MP4 audio.
Arrival sequence remains the replay order across domains.

## Observations

Provisional transcript updates and face observations enter through the coordinator.
They update live engine state but cannot create cuts or replace finalized captions.
Delayed callbacks are tagged with their original capture session and ignored after finalization or failure.
Raw PCM and camera frames are not placed in the event journal.
Vision updates are coalesced and deduplicated, so the journal is an observation history rather than a record of every processed frame.

## Recovery

Capture journals remain separate until valid media is finalized.
The completed project adopts the same UUID and live history while preserving any existing edit decisions.
After process restart, unfinished journals become interrupted.
Only videos validated by the existing recovery path can become finalized projects.
Missing and undecidable partial media keep an interrupted history for diagnosis.
Recovery is idempotent and never restarts recording or recognition automatically.

## Verification

Pure replay fixtures cover normal capture, rapid stop, cancellation, failure, interrupted recovery, and late observations.
Store tests cover reopening, sequence preservation, corruption, and selective cleanup.
Device tests cover actual start/stop, backgrounding, Activity recreation, and process termination using only test-owned recordings.
Existing caption/edit/export tests remain required.
