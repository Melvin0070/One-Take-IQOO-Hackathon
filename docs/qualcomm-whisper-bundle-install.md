# Qualcomm Whisper bundle installation

> Historical prototype record: the implementation and measurements described here are archived under `experiments/` and excluded from the current nine-module build.
> Run the historical build commands from the [tested prototype checkout](../experiments/AGENTS.md), not from the current repository root.

This slice prepares the public Qualcomm Whisper Tiny Voice AI v0.61.0 release for the iQOO 15 caption runtime.
The target identity is I2501 / SM8850 / V81.
The graph runtime still owns execution, while this installer owns artifact acquisition, integrity, and lifecycle.

## Pinned release

The archive URL is pinned to the v0.61.0 Voice AI asset for Snapdragon 8 Elite Gen 5 for Galaxy.
The archive is 106,106,008 bytes with SHA-256 `086017959cd4e208c0711c0297d8d9d2e5031677082a1e2063651519917f9820`.
The extracted encoder, decoder, vocabulary, metadata, and config files each have independent size and SHA-256 pins in [WhisperBundleSpec.kt](../experiments/recorder-engine-android/src/main/java/com/onetake/engine/android/whisper/install/WhisperBundleSpec.kt).
The corresponding three-artifact verifier manifest is [whisper-tiny-v0610-sm8850-manifest.json](../tools/model-bundles/whisper-tiny-v0610-sm8850-manifest.json).

The Qualcomm model card says that the original Whisper implementation is Apache-2.0 and links the versioned Transformers license.
The model card also directs on-device deployment through the Qualcomm Voice AI SDK.
The public archive and SDK may carry separate distribution conditions.
The app must review those conditions before packaging or redistributing the files.
The installer records the source and license references but does not assert that the repository has a redistribution grant.

## Atomic layout

An installer stores data below the app's `noBackupFilesDir/models/qualcomm-whisper` directory.
Each successful extraction is written to a unique `v-*` directory.
The `active` file contains only the selected version directory name.
The pointer is written to `active.part`, flushed, and atomically renamed into place after every artifact has passed verification.
The previous version is retained so a failed, cancelled, or interrupted update cannot remove the last valid bundle.
Reinstalling an already valid pinned archive returns the existing active directory.

`resolveInstalled(context)` validates the pointer, containment, regular-file status, exact sizes, and exact digests before returning a directory.
It returns `null` when no verified active bundle is available.
No caller should open files from a staging directory or infer readiness from the pointer alone.

## Host preparation

The standard-library host installer verifies the archive and uses the same versioned pointer layout:

```sh
python3 tools/model-bundles/install_bundle.py \
  --archive /path/to/whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip \
  --destination /path/to/qualcomm-whisper
```

The optional downloader accepts only the pinned HTTPS URL and rejects redirects outside that URL.
It limits the response to the pinned archive size, checks the archive digest, and accepts a cancellation callback.

The Android installer takes a local archive so the app can choose its own approved transport and permission flow.
Call it from a worker thread because hashing and extraction read approximately 101 MB of compressed model data.
Pass `WhisperInstallCancellation` from a lifecycle-owned job when the operation must be cancelled.

## Runtime handoff

The returned directory exposes `encoder.bin`, `decoder.bin`, and `vocab.bin` for the direct QNN Whisper graph adapter.
`metadata.json` and `config.json` remain available for shape, release, and runtime checks.
This slice does not activate production captions, tokenize audio, generate timestamps, or select a CPU fallback.
Those concerns remain in the caption integration work and must consume `resolveInstalled(context)` rather than bypassing integrity checks.

The installer tests exercise successful installation, idempotent reuse, archive corruption, cancellation, traversal, unsupported entries, oversized and incomplete artifacts, and invalid active pointers.
The host installer was also run against the downloaded public archive in the local model cache and verified all five files at their pinned sizes.
