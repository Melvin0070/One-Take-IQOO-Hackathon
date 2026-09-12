# Engine foundation

Script features and performance optimization are deferred.
The engine preserves the existing single-recording caption and reversible-cut workflow and records the live capture lifecycle.

## Boundaries

`:engine` is a Kotlin JVM library with no Android, model, media, or UI dependencies.
It owns sample-based time conversion, session transitions, caption and cut validation, edit projection, and event replay.
`:app` owns CameraX, Whisper, MediaPipe, file persistence, presentation, and Media3 adapters.
Existing caption and edit sidecars remain readable and are imported into a recording's engine ledger when needed.
Migration accepts a legacy duration rounded by at most 250 ms when every cut remains inside the actual media boundary.
Larger mismatches fail without overwriting the old metadata.
Original media is never changed by the engine.

## Ordering and time

The engine uses 16 kHz sample units with explicit MEDIA, RECOGNIZER, and CAPTURE_ESTIMATE clock domains.
Only finalized MEDIA positions may drive captions and edits; provisional microphone and monotonic capture estimates remain observations.
A video anchor explicitly maps sample positions to video presentation time.
Adapters convert the existing finalized-MP4 timestamps at their boundary; this does not claim a hardware microphone clock anchor.
The decoder places audio samples at their MP4 presentation timestamps, including initial silence, so the current adapter uses the identity anchor `(0, 0)`.
Events have both a session ID and a contiguous arrival sequence.
Sequence determines replay order; sample position describes the observation, not when it arrived.
Late final transcripts are accepted after media finalization without reordering committed events.
Equal sample positions are permitted and distinguished by sequence.
The synchronous engine serializes submissions and publishes new state only after the event sink has durably accepted the event.

## Persistence and recovery

Each capture receives a durable UUID journal before CameraX starts.
A completed recording has a content-bound project ledger in app-private no-backup storage that adopts the capture UUID and history.
Adoption preserves any captions and edit decisions already imported during recovery.
Unfinished capture journals become interrupted after process restart, and valid recovered media can finalize the same session.
Each record has a sequence, encoded payload, and checksum.
Writes are locked, flushed, and synced before state is published.
A trailing incomplete record is ignored and removed before the next append.
A corrupt completed record fails visibly rather than discarding valid history.
Replay validates every transition and sequence.
Cancellation and missing media prevent further edits in that session.
Reading a known project whose source has disappeared persists its missing-media state.
New caption results outside the finalized media duration are rejected with an explicit error rather than silently discarded.
Undo and restore append new decisions; they never erase originals or history.

## Acceptance

1. JVM tests cover time mapping, edit projection, lifecycle, late events, replay, immutable state, and failed persistence.
2. Journal tests cover reopening, Unicode, interrupted writes, corruption, and competing writers.
3. App adapters migrate existing metadata and route caption/cut reads and writes through engine state.
4. Preview and export use the same engine-derived kept ranges and caption mapping.
5. Existing device editing and live recording tests pass without uninstalling the app or deleting user data.

## Explicit limits

This milestone does not persist raw microphone buffers or resume recognition after process death.
The existing VideoStore validates interrupted MP4 files before the live engine adopts recovered media.
A process killed before a valid MP4 is written may leave no recoverable footage; the interrupted journal is retained.
See [live engine connection](live-engine-plan.md) for the adapter lifecycle and clock contract.
It does not introduce scripts, take grouping, multicam, replacement models, or accelerator work.
Media processing jobs remain application-scoped and require the app to remain open.
