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
