# Downloadable Offline Captions

The first marketplace feature is offline captions for a 30-second recording.
The APK contains the runtime and editing code; the marketplace downloads a version-pinned multilingual Whisper Tiny model from a public repository.
Install requires an expected size and SHA-256 check, and interrupted downloads never appear installed.
Users can enable automatic captions after recording, retry failed downloads, or remove the model without deleting videos or saved captions.

The app preserves raw videos and stores timed captions separately.
Review shows synchronized captions and allows text corrections.
Export writes a new MP4 with captions burned in and preserves the original.
Long-running operations expose progress and errors and protect the model from removal while in use.
The first implementation targets offline CPU inference on the connected ARM64 phone; no NPU performance claim is made.

Verification covers package integrity, installation lifecycle, real offline transcription, caption timing, MP4 playback, and original-file preservation.
Use reinstall-in-place and direct instrumentation; never uninstall the app or use connectedDebugAndroidTest.
