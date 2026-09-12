# Recorder quality improvements

The approved scope is recording correctness, durable access to videos, simpler structure, accessibility, and meaningful tests.

1. Reproduce the existing camera and review flow on an available Android device.
2. Extract camera control and publish one observable recording state, including synchronous startup and finalization.
3. Preserve finalized videos and recover interrupted files through a small file store with filesystem tests.
4. Separate permissions, camera UI, review UI, and navigation from MainActivity.
5. Add a saved-video library with playback and confirmed deletion.
6. Make Back preserve recordings and require confirmation for destructive retakes.
7. Move interface text into string resources and label the recording control for accessibility.
8. Run unit tests, lint, APK builds, and device instrumentation covering recording, saved access, review navigation, and lifecycle interruption.

Existing videos must be preserved.
No Git repository exists in this directory, so no commit or branch will be created.

Completed and verified.
See `verification.md` for checks, limitations, and the initial test-runner data-loss incident.
