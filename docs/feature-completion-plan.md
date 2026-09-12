# One-Take feature completion

The user has deferred speed optimization until the features are complete.
The approved One-Take design remains the product specification.
The final device is iQOO 15 with Snapdragon 8 Elite Gen 5; it is not available in this session.
CPU inference remains functional while accelerator and thermal optimization are deferred.

## Milestone 1: Reversible single-phone editing

- Add edit-decision metadata referencing raw-file timestamps, with stable cut IDs and enabled state.
- Detect conservative long silences from decoded PCM and retain padding around speech.
- Preserve source video and independently stored captions.
- Allow per-cut undo/redo in review and restore all cuts.
- Preview selected raw ranges and render the same ranges with captions mapped to the final timeline.
- Persist three caption presets and use the chosen preset in preview and rendered output.
- Share a completed video through a scoped FileProvider URI.
- Verify actual recordings, saved decisions across recreation, caption timing after cuts, exported duration/audio, and unchanged raw hashes.

## Milestone 2: Capture guidance and reframing

- Add face-based framing signals and an optional smoothed crop with persisted source timestamps.
- Add pre-take setup coaching and confidence-gated prompt chips.
- Add flub/mumble/gaze candidate marks without treating uncertain speech as automatic deletion.
- Verify fallback when vision is unavailable and continuity of raw capture.

## Milestone 3: Director assembly

- Add explicit retake grouping followed by transcript-aligned best-take suggestions and reversible selection.
- Add cutaway slots, capture guidance, and narration-preserving insertion.
- Add post-stop metadata only after capture/editor behavior works.

## Milestone 4: Multicam

- Add local pairing, timestamp offset measurement, locally recorded originals, and low-resolution previews.
- Add camera roles, bounded switching, primary audio, selected-segment transfer, and export.
- Verify using real devices when available and expose unavailable connections honestly.

## Ownership and validation

Separate workers own the edit-domain package and caption-preset presentation.
The parent owns integration, storage cleanup, navigation, exports, and acceptance testing.
No workers edit the same files concurrently.
Direct instrumentation and reinstall-in-place preserve existing app recordings and model downloads.
The directory is not a Git repository; baseline copies support review without pretending commits exist.
Every milestone is checked with local tests, build/lint, and available real-device flows before proceeding.
