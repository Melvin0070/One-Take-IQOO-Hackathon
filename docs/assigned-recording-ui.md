# Assigned recording UI issues

GitHub CLI identity: `AlwinSunil` (Alwin Sunil).
Base: `origin/main` at `974c489`, freshly pulled on 2026-09-12 after the requested local cleanup.
All three assigned issues have `priority:p0` and `demo:2h`; dependencies determine the order.

| Order | Issue | Implementation |
| --- | --- | --- |
| 1 | [#55 Home and mode selection](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/55) | Home, two mode cards, saved mode, Projects entry to the existing library, and idle Camera Back to Home. Existing recorder tests enter through Assisted Mode. |
| 2 | [#56 Script entry](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/56) | Multiline entry, clipboard paste, pluralized word count, blank-input guard, persistent draft, atomic accepted-script/draft transaction, and camera script overlay. |
| 3 | [#59 Assisted live transcript](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/59) | Existing live segments feed the shared overlay; measured text follows the newest three wrapped lines. Missing/disabled model links to the marketplace before capture. Script Mode excludes the transcript overlay. |

## Integration boundaries

`RecordingMode` is app navigation state, not a competing engine event model.
`RecorderContent` keeps it through recreation and passes mode and script to `CameraScreen`.
Long scripts live in `RecordingSetupStore` rather than the saved-instance-state Binder bundle.
Draft edits use SharedPreferences persistence; Continue checks a background `commit()` before navigating.

[#57 camera wiring](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/57) remains open and depends on the new [#45 RecordingSession model](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/45).
It must consume this selected mode and accepted script when creating the new session header.
This change does not claim mode/script journaling, new detectors, or analysis/editor handoff.
The current live-caption session and recording finalization remain in use.

`RecordingOverlay` provides a manually scrollable script view so entry has a usable camera destination.
[#58](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/58) should replace that branch with the matcher-driven view after [#48](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/48) and #57 land.
Automatic progress and skip events are not implemented here.
The Projects button opens the current Library; project migration and editing remain #53/#63.

## Verification

See [current verification](verification-current.md) for commands, device results, and host-test limitations.
The pull request references #55, #56, and #59 without automatically closing them, so the remaining integration boundaries can be reviewed explicitly.
