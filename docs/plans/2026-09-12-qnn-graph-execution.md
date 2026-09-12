# QNN graph execution implementation plan

> Historical prototype record: the implementation and measurements described here are archived under `experiments/` and excluded from the current nine-module build.
> Run the historical build commands from the [tested prototype checkout](../../experiments/AGENTS.md), not from the current repository root.

Goal: execute the official Whisper Tiny encoder and decoder through an engine-owned Android HTP session on the iQOO 15.
This is the native execution layer for issue #3; caption decoding, timestamps and production activation remain subsequent integration work.

## Design

Load QNN HTP and QNN System dynamically from the app native library directory.
Read context-binary graph metadata and copy tensor metadata before releasing the system context.
Create the HTP backend, device and context, retrieve one graph, and execute raw typed tensor buffers.
Expose immutable tensor names, dimensions, data types and checked byte sizes to Kotlin.
Serialize native execution and release, reject incorrect inputs, and fail explicitly when the SDK is absent.
Do not register CPU fallback inside this runtime or mark the production caption model validated.
Keep vendor artifacts outside Git.

## Tasks

- [x] Implement `QnnGraphSession` and JNI context loading/execution in `experiments/recorder-engine-android`.
- [x] Add checked tensor metadata and input validation tests.
- [x] Package local QnnSystem with the existing optional QAIRT build.
- [x] Add an opt-in Android device test using the exact downloaded encoder and decoder hashes.
- [x] Feed encoder cross-attention outputs to the decoder and verify finite logits and repeatable session lifecycle.
- [x] Run targeted build, unit tests and physical-device instrumentation without uninstalling the app.
- [x] Review native ownership and error paths.

Delivery uses a separate branch and PR based on the bundle-verification branch.

## Reference evidence

The public Qualcomm Voice AI Whisper Tiny v0.61.0 bundle provides encoder.bin, decoder.bin and vocab.bin.
It declares QAIRT 2.45.0.260326154327 and SM8850/V81 reference-device compilation.
On the owner’s I2501, QAIRT 2.50 qnn-net-run executed both HTP graphs, with encoder outputs feeding the decoder.
The decoder produced 51,865 finite FP16 logits from synthetic inputs.
Detailed QNN profiles include accelerator execution times and operation cycle counts.
This reference run is graph-execution evidence, not caption accuracy or streaming validation.
