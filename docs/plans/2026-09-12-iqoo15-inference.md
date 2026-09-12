# iQOO 15 inference implementation plan

**Goal:** Put production model execution behind an engine-owned NPU preference policy and implement the validated Qualcomm path for the owner’s iQOO 15.
**Spec:** [Issue 1](https://github.com/Melvin0070/One-Take-IQOO-Hackathon/issues/1).
**Architecture:** Pure Kotlin engine contracts own selection, lifecycle, and execution reports.
Android adapters supply model-specific sessions and QNN/HTP integration.
Existing CPU inference remains recovery behavior until each replacement artifact has passed numerical and on-device validation.

## Constraints

Target only I2501 / SM8850 on the connected iQOO 15.
Preserve recordings, model storage, caption timestamps, VAD state, and face geometry semantics.
Never label backend availability or initialization as successful NPU inference.
Do not replay failed stateful inference automatically.
Do not commit vendor SDK binaries, model downloads, device traces, or user content.

## Work sequence

- [x] Verify current backend configuration and baseline engine/app tests.
- [x] Isolate work on `feat/iqoo15-npu-engine`.
- [x] Implement and test engine model descriptors, policy selection, serialized sessions, and execution reports.
- [x] Establish Android runtime/artifact availability using the local QAIRT SDK and primary documentation.
- [x] Integrate production model lifecycle with the engine and explicit fallback reasons.
- [ ] Implement and verify QNN/HTP execution with compatible model artifacts where available.
- [x] Run engine/app unit tests, lint, APK builds, and direct instrumentation without uninstalling the app.
- [x] Review the actual diff and fix findings.
- [x] Push reviewed commits and open a PR documenting verified execution and remaining blockers.

## Validation

Engine tests must fail for incorrect NPU preference, hidden mixed/CPU execution in strict mode, swallowed cancellation, premature execution claims, state replay, and use after close.
Android tests must verify the actual app adapters, not standalone SDK samples.
Full issue completion requires HTP graph evidence for Whisper, Silero, and face tracking plus task-quality and whole-recorder validation on the same phone.
Keep the issue open and PR in draft if these requirements are not met.

## Remaining acceptance work

The QNN probe is validated, but no compatible production replacement artifact was produced or executed on HTP.
Whisper, Silero, and face tracking remain CPU fallbacks, and issue 1 remains open.
The draft PR records the model-conversion prerequisites and the separate foreground/export validation limitation.
