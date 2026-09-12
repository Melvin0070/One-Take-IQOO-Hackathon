# libfvad source pin

This directory vendors the WebRTC voice activity detector through libfvad.

- Upstream: https://github.com/dpirch/libfvad
- Revision: `532ab666c20d3cfda38bca63abbb0f152706c369`
- Retrieved from: `https://github.com/dpirch/libfvad/archive/532ab666c20d3cfda38bca63abbb0f152706c369.tar.gz`
- Public API: `include/fvad.h`
- License and patent grant: `LICENSE` and `PATENTS`

Only the library sources needed by the CMake target are included.
The Android wrapper configures a 16 kHz, 20 ms stream in WebRTC VAD mode 0.
