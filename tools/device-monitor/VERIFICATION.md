# Verification

Verified on 2026-09-11 using Python 3.13 on macOS and a Samsung SM-A528B running Android 14 (SDK 34, SM7325 chipset).

## Automated checks

`python3 -m unittest discover -s tools/device-monitor -p 'test_*.py' -v` passed 47 tests.
The suite covers counter arithmetic, battery units and charging state, thermal parsing, device selection, safe package arguments, recording through a fake ADB executable, and dashboard validation/aggregation/HTTP refresh, Perfetto export semantics, static path confinement, asset checksum rejection, and binary capture transfer.
An additional regression verifies that probe timestamps identify collection completion while preserving the start time separately.

## Real device

ADB discovery found Android Studio's SDK installation automatically.
Device discovery and capability probing succeeded.
A six-second recording produced seven samples with no collector errors.
A later ten-second recording with a two-second interval produced six samples over 10.627 seconds, with no collector errors.
Its collection wall time total was 3627.868 milliseconds, which is not a measurement of incremental CPU overhead.
The initial CPU sample was warming up, followed by five available CPU measurements.

The phone exposed memory, battery temperature/voltage/level, thermal severity, and seven current thermal sensor readings.
The unlabelled Samsung battery-current value was preserved as raw vendor data, with normalized current explicitly unavailable.
A full plugged-in battery was reported as powered and not charging.
GPU and NPU utilization remained unsupported.
Three thermal sensors reported zero; these are unvalidated vendor readings, not confirmed physical temperatures.

The recorder package had no running process and correctly returned unavailable package memory.
A separate probe and the saved baseline measured the running `com.android.systemui` process successfully.

## Local Perfetto and comparison

Installed the unchanged official Perfetto UI `v58.3-11fbaed83` with all 76 manifest resources verified against SHA-256 digests.
A five-second native capture produced an 824,479-byte trace on the connected Samsung.
The browser loaded it successfully with CPU scheduling, CPU frequency, and process tracks.
The initial direct stdout capture was rejected by Perfetto because ADB merged diagnostic text into the binary stream.
The recorder now captures to a unique remote file and pulls its binary bytes separately, with a regression test protecting that behavior and existing files.

A 31-sample monitoring session over 30.705 seconds also loaded into the real local UI through `dashboard.py` at port 8765.
Browser inspection showed CPU, memory, battery, package memory, and thermal counters, plus unavailable GPU/NPU diagnostic tracks.
No import-error dialog appeared.
The original custom dashboard was removed.
The API rereads ongoing sessions, but an already-open Perfetto timeline remains a snapshot until the trace URL is reopened.
Summary export and comparison commands retain the previous behavior.
The comparison runs exercised the tool only; no app optimization was made or demonstrated.

Local evidence files are under `runs/`, which is excluded from future Git tracking.
They include `samsung-baseline.ndjson`, `samsung-repeat.ndjson`, `samsung-summary.json`, `samsung-comparison.json`, `timeline-validation.ndjson`, `native-android-clean.pftrace`, `local-perfetto.png`, and `local-perfetto-sampled.png`.

## Remaining coverage

Only one physical phone was tested.
Other vendors, Android versions, host operating systems, physical USB disconnects, and sustained long recordings have not been validated on hardware.
GPU/NPU adapters, thermal headroom, in-app event markers, automatic native/sample trace merging, and an automated AI optimization loop are not implemented.
No recorder app code was changed, so its Android build and instrumentation suites were not rerun.
The workspace has no Git metadata; no commit or push was performed.

## Recorder VAD validation, 2026-09-12

No monitoring implementation changed.
During recorder export regression testing on Samsung SM-A528B, a ten-second native trace was recorded with `perfetto_local.py record`.
`runs/silero-validation-20260912.pftrace` contains 33,421,842 bytes.
It loaded in the local official Perfetto UI at port 10012, with CPU scheduling, CPU frequency, and `com.example.myapplication` tracks visible.
The browser reported no console errors; a screenshot is saved beside the trace.
No speed, NPU, or thermal improvement is inferred from this trace.
Recorder feature evidence is in the root `docs/verification.md`.

The final pause-boundary build was also traced for 10 seconds during device export validation.
`runs/silero-boundary-validation-20260912.pftrace` contains 33,465,581 bytes and loaded at localhost port 10013 in the official Perfetto UI.
CPU 0–7 scheduling/frequency and app process 14307 were visible, with no console error; the corresponding PNG is retained.
This verifies trace capture/import only, not a performance improvement.

## iQOO NPU and local LLM validation, 2026-09-12

Hardware: iQOO 15 I2501, SM8850, Android 16, serial `10BFAU14Y0000XR`, macOS host.
QAIRT runtime: `2.50.0.260828`; Android NDK: `30.0.16248370`.
The earlier single-phone coverage statement describes the September 11 baseline only.

The corrected thermal collector exposed eight type 9 NPU sensors and excluded non-temperature types 6, 7, and 8.
`runs/iqoo-npu-baseline.ndjson` loaded in local Perfetto at port 8766 with eight NPU temperature tracks.
`runs/iqoo-qwen-npu.ndjson` contains 46 samples across a 45-second experiment, including upload and a short query.
Its observed NPU sensor range was 29.4–43.8 Celsius; this does not establish sustained LLM thermal behavior or causation.

`genie_run.py` executed the official Qwen3-0.6B w4a16 Snapdragon 8 Elite Gen 5 bundle using explicit QnnHtp engines.
The bundle was compiled with QAIRT 2.45 and successfully ran with the installed 2.50 runtime twice.
The final command used `--max-tokens 128`, the prompt in `runs/qwen-prompt.txt`, and output `runs/qwen-npu-verified`.
The saved `run.json` reports 37 prompt tokens, 112 generated tokens, 43,766 microseconds to first token, and 121.6249 generated tokens/second.
These are runtime-reported values from one short query, not a sustained benchmark.
The run returned zero, produced text and 6,089 trace events including 224 QnnApi::graphExecute calls, and removed its remote staging directory.
The source and executed configuration hashes are recorded separately.

Raw Genie traces contained crossing synchronous slices that caused Perfetto import errors.
`python3 tools/device-monitor/genie_trace.py tools/device-monitor/runs/qwen-npu-verified/profile.json tools/device-monitor/runs/qwen-npu-verified/perfetto.json` preserved source event timing and placed crossing slices on explicitly named visual lanes.
The derived trace opened in the official local UI at port 10003 with visible Genie tracks.
Perfetto Overview reported zero import errors, trace errors, data losses, and notices.
The raw SDK profile is retained unchanged; synthetic lanes are not additional hardware threads.

`qnn_smoke.py` also built and executed the SDK quantized convolution/ReLU sample on HTP for two inferences.
Evidence is in `runs/qnn-iqoo15-20260912T000005Z/manifest.json`, `htp-run.log`, and `htp-profile.csv`.
The detailed profile CSV contains 3,879 bytes with accelerator execution times, cycles, and HVX thread counts.
Remote staging was removed.
The separate CPU comparison failed its 0.001 absolute tolerance, with maximum error about 0.1094; numerical equivalence is not validated.
The optional Chrome profile reader failed for this detailed-profile capture, and that failure remains recorded.
This sample's detailed accelerator measurements are separate from the LLM's framework trace events.

Final automated command: `python3 -m unittest discover -s tools/device-monitor -p 'test_*.py'`.
All 70 tests passed after the final exclusive-output safeguard.
NPU utilization percentage, frequency, power, synchronized system/LLM traces, sustained performance, and in-app model integration remain unverified or unimplemented.
