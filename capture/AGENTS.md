# :capture — camera, audio, the anchor

CameraX video, `AudioRecord`, the foreground service, the WAV writer, and the one
`VideoAnchor` per session. Produces `AudioFrame`s for `:engine`.

## Do the probe before any feature code

**The highest-value thirty minutes of the build.** Can this app run CameraX `Recorder` with
audio enabled *and* its own `AudioRecord` at the same time, on this phone?

Start CameraX with `withAudioEnabled()`, start a `VOICE_RECOGNITION` `AudioRecord`, run
10 s, assert **all three**:

1. `recordingStats.audioStats.audioState == AUDIO_STATE_ACTIVE` and `getAudioAmplitude() > 0`
2. `AudioRecord` buffers are **not all zero** — check the bytes directly
3. `AudioRecordingConfiguration.isClientSilenced() == false`

**Run both start orders.** The CDD's tiebreak for equal-priority capturers is "most recently
started wins"; if OriginOS reuses that path for same-app clients, order decides who gets
audio.

**Do not trust the callback alone.** `registerAudioRecordingCallback()` must be registered
*before* `startRecording()`, and it fires "only when the app is receiving audio and a change
occurs" — silence from frame zero may never fire it.

## Architecture B, with A's data captured anyway

Build B: CameraX muxes its own audio and the MP4 *is* the cut source; sync tolerance is
hundreds of milliseconds because every cut boundary lands in a speech pause.

**Write the WAV unconditionally**, even under B where you do not need it. Then if B breaks,
the fallback is a playback-path swap rather than a re-architecture at hour 20. Under B the
WAV costs a few MB. Under A it is already on disk.

Full reasoning and both architectures' holes: `docs/agents/landmines.md` L9 and
`docs/agents/architecture.md`.

## Foreground services

`camera` and `microphone` **cannot be started from the background** — on Android 14+ that
is a `SecurityException` thrown immediately at `startForeground()`, not a degradation. Call
`startForegroundService()` while an activity is visible. `PermissionChecker
.checkSelfPermission()` does **not** protect you; it returns `PERMISSION_GRANTED` in the
background too.

## The clock

You own the sample counter. Count **every** sample you produce; it is the master clock for
the whole system and nothing downstream has another one. Emit exactly **one** `VideoAnchor`
per session — that is the single place video time enters the engine.

`SENSOR_INFO_TIMESTAMP_SOURCE` is read through `Camera2CameraInfo.from(cameraInfo)`, or
`Camera2Interop.getCameraCharacteristics(cameraInfo)` on CameraX 1.7.0-alpha03+ where the
former is deprecated.

## Storage — your critical gap

1080p is roughly 100 MB/min and **a full disk currently truncates the session silently.**

Do not estimate the bitrate; CameraX publishes none. Query it:

```kotlin
CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P).videoBitRate
```

Then poll `RecordingStats.getNumBytesRecorded()` on `VideoRecordEvent.Status` — exact and
free — stop at a threshold, and mark the session truncated.

## R23 — the audio route

The Bluetooth remote connecting can move the mic. Emit `AudioRouteChanged` into the ledger.
An unexplained accuracy cliff at 03:00 is unfixable without that line.
