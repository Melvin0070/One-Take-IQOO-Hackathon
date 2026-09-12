# Capture and caption design target

Status: speed and accelerator work deferred by the user in favor of feature completion.
The active plan is `docs/feature-completion-plan.md`.

The current task completes the existing capture and caption flow before adding automatic cuts, Director, or multicam.
The accepted target remains offline multilingual captions, preserved originals, and a captioned 90-second 1080p export within 10 seconds after Stop on the iQOO 15 (Snapdragon 8 Elite Gen 5 / SM8850), including take five.
The connected Samsung SM-A528B provides development evidence only.

## Execution

1. Reproduce the current real recording flow and separate post-stop transcription, reference-audio decoding, alignment, and rendering costs.
2. Correct overlapping-caption stability defects with regression coverage that preserves intentional repetitions.
3. Apply measured pipeline improvements without reducing resolution, dropping frames, skipping alignment, or discarding originals.
4. Add a repeatable 90-second and five-take device benchmark with stage timings and thermal measurements.
5. Verify build, tests, and actual recordings, and record achieved results separately from unmet targets.
6. Validate accelerator deployment against the exact target chipset and benchmark Hinglish on held-out human recordings when those inputs are available.

## Constraints

Do not uninstall the app or use connectedDebugAndroidTest because recordings live in private storage.
Use reinstall-in-place and direct instrumentation.
Do not claim NPU execution for the current CPU runtime.
Do not substitute a synthetic English test for a Hinglish accuracy benchmark.
Do not add the next product feature while this target is incomplete.

## Accelerator boundary

The installed multilingual model is whisper.cpp GGML and the packaged native runtime is CPU-only.
Qualcomm AI Hub documents SM8850 acceleration, but that does not make this model compatible with QNN or validate retail iQOO firmware.
An NPU implementation requires an appropriate model export, compatible preprocessing/tokenizer/timestamp decoding, runtime packaging, and real-device profiling.
No NPU execution or Hinglish accuracy win is claimed by this change.

References:

- [Qualcomm supported devices](https://workbench.aihub.qualcomm.com/docs/hub/devices.html)
- [Qualcomm model compilation](https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html)
- [Media3 Transformer](https://developer.android.com/media/media3/transformer)
