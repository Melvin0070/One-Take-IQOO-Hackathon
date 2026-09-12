# Qualcomm Whisper bundle verification plan

Goal: make the first caption replacement prerequisite independently reviewable through an offline model bundle verifier.
Architecture: a standalone Python standard-library CLI validates the manifest and files before any future native adapter consumes them.
This is a development preflight tool, not an Android loader or evidence of NPU execution.
Spec: [caption issue #3](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/3).

## Scope

Target only I2501 / SM8850 / V81.
Require schema version, model identity and version, declared QAIRT and Voice AI versions, source and license references, and encoder/decoder/vocabulary file records.
Require safe relative paths, unique roles, positive sizes and SHA-256 integrity.
Reject missing or corrupt files, malformed metadata and paths escaping the bundle.
Do not download, install, load native code or enable production NPU selection.
Do not commit vendor binaries, credentials or synthetic hashes presented as real model metadata.

## Implementation and verification

- [x] Add failing tests in `tools/model-bundles/test_verify_bundle.py` for complete synthetic bundles and invalid metadata/files.
- [x] Implement `tools/model-bundles/verify_bundle.py` with concise nonzero failures and JSON success explicitly reporting `model_execution_verified: false`.
- [x] Exercise the actual command using temporary bundle files, including a corrupt-file failure.
- [x] Document manifest preparation, the invocation and its limitations in `tools/model-bundles/README.md`.
- [x] Run the complete focused test suite and review the actual diff.
- [ ] Push a separate branch and open a PR based on `feat/iqoo15-npu-engine`, keeping #3 open.

## Subsequent PR boundaries

The caption runtime adapter requires inspecting the actual Voice AI Android API and matching encoder/decoder/vocabulary artifacts.
The speech detection decision requires validating Qualcomm VAD API semantics and graph placement before choosing it over Silero ONNX.
The face adapter requires mapping the current required landmarks to candidate model outputs before replacing the complete MediaPipe task.
Each production adapter requires real iQOO 15 execution and quality evidence before activation.

## Verification evidence

The focused suite passed 19 tests on 2026-09-12.
A separate CLI invocation accepted a synthetic three-file bundle with exit 0 and `model_execution_verified: false`.
Replacing encoder bytes while preserving its size caused exit 2 with `artifact encoder sha256 mismatch`.
No production model, Android build or physical-device inference was exercised by this tooling change.
