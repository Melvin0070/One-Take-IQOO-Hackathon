# libs/ — vendored binaries

Artifacts that are not available as a trustworthy Maven coordinate. Everything here is
checked into git deliberately, with its reason. `flatDir { dirs("libs") }` in
`settings.gradle.kts` makes them resolvable.

## sherpa-onnx — REQUIRED, not yet present

Download `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (or newer) from the sherpa-onnx
GitHub **release assets** and drop it here, then uncomment the dependency in
`asr/build.gradle.kts`.

**Two things will go wrong if you take a shortcut:**

1. **v1.13.7 and earlier produce EMPTY streaming output forever on Snapdragon 8 Elite Gen 5.**
   No crash, no exception, no log line. v1.13.8 pins onnxruntime 1.28.2, which fixes it.
   Swapping only `libonnxruntime.so` does not work — the JNI lib requires
   `OrtGetApiBase@@VERS_1.27.0`. Take the whole AAR.
2. **The JitPack coordinate silently gives you the DYNAMIC variant.** sherpa is not on
   Maven Central; JitPack publishes `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`,
   but its `jitpack.yml` wraps the dynamic build — larger, and able to collide with a second
   ONNX Runtime at `dlopen` time rather than at build time.

The static-link variant is **7.5 MiB smaller per ABI** (23.0 vs 30.5 MiB) and exports zero
`Ort*` symbols, so it cannot collide with anything now or later. Size matters: every build
is sideloaded to three phones.

Full detail: `docs/agents/landmines.md` L1 and L2.

## LiteRT NPU runtime libraries — REQUIRED for :npu, not yet present

Not vendored here. Nine `.so` files from `litert_npu_runtime_libraries_jit.zip`, taken from
the **GitHub release of the same tag as the Maven AAR** (2.2.0), go into
`app/src/main/jniLibs/arm64-v8a/` — which is exactly where LiteRT's
`getLibraryDir()` looks, so Play AI Pack delivery can be skipped entirely.

**Use `qualcomm_runtime_v81`** for SM8850 (iQOO 15). Google's own example ships `v79`,
which is the wrong Hexagon version. Add the rehearsal phone's `v75`/`v73` Skel+Stub too.

**Never let the AAR version and the `.so` version drift.** The symptom is
`dlopen failed: cannot locate symbol "LiteRtQualcommOptionsGet"` followed by the app running
happily on CPU while logging that the NPU initialised successfully.

Full detail: `docs/agents/landmines.md` L7.

## Models are NOT committed

Model files stay out of git. They arrive on the device over file transfer, are imported
through the app's import screen, and are **SHA-256 verified once** against a checked-in
manifest (R11). A tampered model is rejected at import.

## Licenses

Anything added here must also be added to `config/attribution-manual.md` — Gradle cannot see
vendored files, so `./gradlew generateAttribution` will not find them, and attribution is
checked at submission.
