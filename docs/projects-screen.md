# Projects screen (#63)

The Home Projects entry, camera thumbnail and permission-screen entry now open a project grid. Each card shows a thumbnail, the first available saved script line (or recording date/time), recording mode, original duration and an Edited indicator when saved cuts are enabled. The entire card opens review; the delete icon retains the existing confirmation.

`ProjectCatalog` now combines [ProjectStore manifests](project-store.md) with the existing engine's current edit state. IDs, creation time, script, mode and thumbnail come from the manifest. Legacy defaults use Assisted mode unless known journaled script data is available. Unreadable history/media retains a visible, deletable card with unavailable details instead of invented zero values. A propagated loading error exposes Retry.

`RecorderContent` invokes existing interrupted-recording recovery and idempotent startup migration, then loads summaries on the I/O dispatcher. Returning from review or changing caption/edit revision reloads the cards. `ProjectReviewRoute` resolves project IDs before opening the existing engine-backed review flow. No copy of an edit plan is stored in navigation state. VideoStore locking/recovery, CameraRecorder and the original recording path are unchanged.

## Remaining integration

The first increment at `ba90938` supplied the grid and existing-editor route. The #53 follow-up adds manifest-backed identity, loading and deletion. Arbitrary reordered-clip rendering remains dependent on #54/#64. Saved timeline JSON is preserved verbatim, and this build offers an explicit recording fallback when it cannot render it. The storage-order and persisted-cut tests do not claim to satisfy the complete reordered-editor acceptance test; #63 should remain open until that integration is verified.

## Verification

See [current verification](verification-current.md) for commands and measured results. New coverage includes catalog defaults/error isolation/cancellation, saved script title and mode after Activity recreation, persistent cut choices across reopening, original-file hash preservation, and confirmed deletion without removing another recording.
