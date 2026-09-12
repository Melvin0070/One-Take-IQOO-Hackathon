# Qualcomm Whisper model bundle verifier

`verify_bundle.py` validates a prepared Qualcomm Whisper model bundle before an Android runtime loads native code.
It uses only the Python standard library and does not download, install, convert, or execute a model.

Run it from the repository root with the manifest path followed by the bundle directory:

```sh
python3 tools/model-bundles/verify_bundle.py \
  /path/to/manifest.json \
  /path/to/bundle
```

A successful command prints JSON with `artifact_integrity_verified: true` and `model_execution_verified: false`.
The latter field is deliberately false because file integrity is not evidence of graph placement or NPU execution.
Validation errors are written as one concise `error:` line on standard error and return a nonzero status.

## Whisper bundle manifest schema version 1

This schema is intentionally limited to the Whisper encoder, decoder, and vocabulary artifacts.
VAD and face model bundles need their own artifact contracts when their conversion work begins.

The manifest is a JSON object with exactly these fields:

```json
{
  "schema_version": 1,
  "model_id": "synthetic/example-model",
  "model_version": "synthetic-1",
  "target": {
    "device_model": "I2501",
    "soc_model": "SM8850",
    "soc_variant": "V81"
  },
  "qairt_version": "synthetic-qairt-version",
  "voice_ai_version": "synthetic-voice-ai-version",
  "source": "https://example.invalid/synthetic-source",
  "license": "https://example.invalid/synthetic-license",
  "artifacts": [
    {
      "role": "encoder",
      "path": "encoder.bin",
      "size_bytes": 1,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    {
      "role": "decoder",
      "path": "decoder.bin",
      "size_bytes": 1,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    {
      "role": "vocabulary",
      "path": "vocabulary.bin",
      "size_bytes": 1,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    }
  ]
}
```

This is an explicitly synthetic schema example.
Its version strings, references, sizes, and zero digests are placeholders and are not Qualcomm production metadata or artifact hashes.
Use measured values from the prepared bundle and authoritative source and license references for a real manifest.

The verifier requires the target to be exactly the tested iQOO 15 identity `I2501` / `SM8850` / `V81`.
All six metadata strings must be nonempty.
The artifact list must contain exactly one each of `encoder`, `decoder`, and `vocabulary`.
Each artifact path must be a unique, safe relative path with no traversal, absolute path, or backslash separator.
Each `size_bytes` value must be a positive integer, and JSON booleans are rejected even though Python represents them as integers.
Each `sha256` value must contain exactly 64 lowercase hexadecimal characters.

For every artifact, the verifier resolves the real path and requires it to remain inside the bundle directory.
It requires the resolved file to be regular, then streams the bytes to verify both the declared size and SHA-256 digest.
Internal symlinks may resolve to regular files inside the bundle, while symlinks that escape the bundle are rejected.
Unknown fields and unsupported schema versions are rejected so that metadata cannot silently change the contract.
Run the verifier against trusted, stable local files and compare digests with an independent authoritative source.
The verifier detects changed or corrupt bytes but does not establish artifact authenticity and does not lock files against concurrent changes.

## Scope of the result

The verifier confirms manifest shape, target identity, file containment, regular-file status, size, and digest.
It does not confirm tensor shapes, operator support, QAIRT or Voice AI compatibility, delegate or HTP graph placement, model quality, timestamps, or execution on the iQOO 15.
Those checks require the selected framework adapter, a prepared runtime, and physical-device inference evidence.

Run the focused tests with:

```sh
python3 -m unittest discover -s tools/model-bundles -p 'test_*.py' -v
```

## Install the pinned Whisper release

`install_bundle.py` prepares the public Qualcomm Whisper Tiny v0.61.0 Voice AI archive for an app-owned model directory.
The command accepts the exact archive URL only through `--download`, or a local copy through `--archive`.
Both paths require the pinned archive size and SHA-256 digest before any extraction begins.

```sh
python3 tools/model-bundles/install_bundle.py \
  --archive /path/to/whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip \
  --destination /path/to/qualcomm-whisper
```

To download the pinned release with the standard HTTPS certificate checks, use:

```sh
python3 tools/model-bundles/install_bundle.py \
  --download \
  --destination /path/to/qualcomm-whisper
```

The installer extracts only `encoder.bin`, `decoder.bin`, `vocab.bin`, `metadata.json`, and `config.json` from the expected archive root.
It rejects duplicate or unsafe paths, symbolic links, unknown entries, incomplete files, oversized files, and digest mismatches.
The destination uses an `active` pointer and version directories so failed or cancelled installs leave the previous verified bundle available.
Repeated installation of the same verified release reuses the active directory.

The Android API has the same contract through `WhisperBundleInstaller`.
Call `WhisperBundleInstaller.resolveInstalled(context)` to obtain a directory only after all five pinned files pass verification.
Call `WhisperBundleInstaller(context).install(archive)` from a worker thread and provide `WhisperInstallCancellation` when the caller can cancel the operation.
The public graph adapter should open `encoder.bin` and `decoder.bin` from the returned directory and use `vocab.bin` for token conversion.

The pinned manifest is [whisper-tiny-v0610-sm8850-manifest.json](whisper-tiny-v0610-sm8850-manifest.json).
It records the three runtime artifacts required by the existing verifier.
The supplementary metadata and config files are also verified by the installer.

The model card labels the original Whisper implementation Apache-2.0 and links its license at [the v4.42.3 Transformers license](https://github.com/huggingface/transformers/blob/v4.42.3/LICENSE).
The precompiled Qualcomm archive is distributed from the public AI Hub asset URL and its own redistribution terms should be reviewed before shipping the files in an application.
This repository does not commit model weights or claim a redistribution grant.
