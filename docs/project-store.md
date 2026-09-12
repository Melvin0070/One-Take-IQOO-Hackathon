# Project storage contract (#53)

`app/.../projects/Project.kt` defines the versioned manifest and `ProjectBundle`.
`ProjectStore.kt` persists manifests under `noBackupFilesDir/projects`; recordings remain in VideoStore and
engine history remains in EngineProjectStore. All store operations must run on an I/O dispatcher.

## API

- `getOrCreate(source, mode?, scriptText?)` creates or reopens the recording's stable project ID. Explicit
  capture inputs take precedence if startup migration raced finalization. Absent known script history, legacy
  recordings default to Assisted mode, no script and no analysis. Available journaled script progress is retained.
- `migrate(sources)` is idempotent and returns the sources that failed, preserving corrupt manifests rather
  than resetting them. The UI supplies the recordings returned by existing recovery. Active outputs are rejected.
- `list()` returns readable projects with existing original files, newest first; malformed manifests surface an
  error. The Projects grid isolates errors by loading each recovered recording independently.
- `load(id)` loads a manifest; `readBundle(id)` loads that revision's analysis and timeline documents as well.
- `save(project)` updates metadata with an optimistic revision check. Retain the returned revision before the
  next save; stale callers must reload rather than overwrite another edit.
- `saveDocuments(project, analysisJson?, timelineJson?)` stores JSON documents verbatim and switches their
  references in one manifest update. Null retains the previous document. Timeline codecs and clip ordering
  remain owned by the engine timeline module; this store does not flatten or rebuild them.
- `delete(id)` delegates original deletion to VideoStore before metadata cleanup. `deleteSource(source)` also
  supports confirmed deletion when a manifest is damaged. Only owned recordings and project assets are removed.

The manifest contains schema version, revision, stable ID, creation time, mode, script, original video path,
journal reference, duration, analysis/timeline paths, caption settings and thumbnail path. Caption settings
snapshot the current preset on creation; callers can save subsequent settings. Thumbnail decoding can be
unavailable, and unreadable media/history retains nullable details. A later readable engine state repairs a
previously unavailable journal/duration reference without changing project identity.

## Integrity

Unknown top-level JSON fields and nested caption-setting fields survive read/update/write. Newer schema versions
can be read when their required fields remain compatible but cannot be overwritten by this implementation.
Paths are confined to the owned recording directory and each project's asset directory. Original source identity,
creation time and existing journal reference cannot be replaced through a metadata save.

A process lock plus a file lock serializes writes across instances and processes. File contents are synced before
AtomicFile publishes them. Analysis/timeline files have unique names and are published before the manifest;
readers see either the previous complete revision or the next complete revision. If an error occurs after a
manifest rename, referenced documents are retained. Superseded documents are retained until project deletion;
compaction and history autosave remain #68. Deletion across original media and multiple metadata files is not a
filesystem transaction; interrupted cleanup can leave metadata for a missing recording.

## UI integration (#63)

Startup performs migration after existing recovery. ProjectCatalog reads identity, date, script, mode and
thumbnail from the manifest and current cut state from the engine. Review navigation resolves the project ID
through `readBundle`, preserving authoritative metadata across Activity recreation. A damaged manifest exposes
Retry and an explicit recording fallback.

The new reorderable editor (#64) and timeline-model PR #74 were not merged at implementation time. If a project
contains a serialized clip timeline, this build explicitly reports that its preview is unsupported and offers to
open the recording without changing that timeline. Storage order preservation is verified; rendering a reordered
timeline in the editor is still the remaining #63 acceptance dependency. See [verification](verification-current.md).
