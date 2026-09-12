# Projects screen (#63)

The Home Projects entry, camera thumbnail and permission-screen entry now open a project grid. Each card shows a thumbnail, the first available saved script line (or recording date/time), recording mode, original duration and an Edited indicator when saved cuts are enabled. The entire card opens review; the delete icon retains the existing confirmation.

`ProjectCatalog` is a UI read adapter over the existing `EngineProjectStore`, not a new persistence store. Script titles and Script Mode come from journaled script progress. Recordings without script progress use the legacy Assisted default. Recording file modification time is the date fallback until #53 supplies a manifest creation time. Unreadable history/media retains a visible, deletable card with unavailable details instead of invented zero values. A propagated loading error exposes Retry.

`RecorderContent` first invokes the existing interrupted-recording recovery, then loads summaries on the I/O dispatcher. Returning from review or changing caption/edit revision reloads the cards. Review continues to load the authoritative journal through `CaptionJobs`; no copy of an edit plan is stored in navigation state. VideoStore locking/recovery, CameraRecorder and the original recording path are unchanged.

## Remaining integration

This is the UI and existing-editor portion of #63, implemented first at the user's request. #53's manifest/ProjectStore, #54's clip timeline and #64's editor were not present at base `9b7f909`. Project-ID loading and arbitrary reordered-clip reopening remain dependent on those contracts. The persisted-cut test does not claim to satisfy #63's reordered-timeline acceptance test. The issue should remain open until that integration is verified.

## Verification

See [current verification](verification-current.md) for commands and measured results. New coverage includes catalog defaults/error isolation/cancellation, saved script title and mode after Activity recreation, persistent cut choices across reopening, original-file hash preservation, and confirmed deletion without removing another recording.
