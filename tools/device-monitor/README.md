# Android Device Monitor

`device_monitor.py` is a read-only Android performance monitor that uses only the Python standard library and ADB.
It discovers the connected device, probes the metrics that the device exposes, and writes a timestamped NDJSON session for the local dashboard or an AI analysis loop.

## Requirements

- Python 3.9 or newer.
- Android platform-tools with USB or wireless debugging enabled on the phone.
- No root access is required for the baseline collectors.

ADB is resolved in this order: an explicit `--adb` path, the `ADB` environment variable, `ANDROID_HOME` or `ANDROID_SDK_ROOT` platform-tools, ADB on `PATH`, and the usual macOS or Linux SDK locations under the home directory.

## Commands

List every connected device, including unauthorized and offline devices:

```sh
python3 tools/device-monitor/device_monitor.py devices
```

Probe one selected phone:

```sh
python3 tools/device-monitor/device_monitor.py probe --serial SERIAL
```

When more than one phone is connected, `--serial` is required.
With exactly one ready device, omit `--serial` to select it automatically.
An unauthorized phone is reported as an actionable JSON error instead of being treated as a zero-valued device.

Record a 30-second session at one-second intervals:

```sh
python3 tools/device-monitor/device_monitor.py record \
  --serial SERIAL \
  --duration 30 \
  --interval 1 \
  --output session.ndjson
```

Add `--package com.example.app` to collect that app's `dumpsys meminfo` total PSS when the device exposes it.
The package name is validated and passed to ADB as a separate argument.
Use `--output -` to stream NDJSON to standard output.
Output files are created exclusively; choose a new filename for each recording.

## Metric coverage

The portable baseline reads aggregate CPU utilization from deltas in `/proc/stat`, memory from `/proc/meminfo`, battery values from `dumpsys battery`, and current thermal status and sensor values from `dumpsys thermalservice`.
Thermal parsing stops before cached threshold and listener sections.
Type 9 NPU sensors are emitted as `npu_temperature_<name>` with a separate `npu_temperature` capability.
Thermal HAL types 6, 7, and 8 are virtual voltage/current/percentage readings and are excluded from Celsius metrics.
Historical recordings made before this correction can contain mislabeled virtual sensors; do not use those fields as temperatures.
The battery current value is retained as `battery_current_raw` when a vendor does not provide a unit, while the normalized `battery_current` metric remains unavailable.

GPU and NPU utilization are emitted with `status: "unsupported"` and a reason until a device-specific profiler or inference backend is available.
Missing fields use `unavailable`, `error`, or `warming_up` with a reason.
The monitor never substitutes zero for a value that the phone did not expose.
Device-reported zero sensor readings are preserved and can represent unused vendor sensors.
The sampled collector does not collect per-thread CPU traces or CPU frequencies; use the native Perfetto capture below for those tracks.
Thermal headroom, vendor accelerator adapters, and in-app event markers are not implemented.

## NDJSON format

Every record contains `schema_version: 1`.
The first record has `type: "session"` and stores the session ID, selected serial, device properties from `getprop`, capability status, and run config.
Each `type: "sample"` record stores monotonic elapsed time, collection overhead for its sequential ADB reads, any collector errors, and a `metrics` object.
Each metric has exactly the fields `status`, `value`, `unit`, `source`, and `reason`.
The final `type: "summary"` record stores `status`, sample count, elapsed time, cumulative collection overhead, completion time, and errors.
Collection overhead is wall time spent gathering readings, not a measurement of the collector's additional CPU load on the phone.
Readings in one sample are sequential, not simultaneous, and a slow collector can exceed the requested interval or duration.

## Actual Perfetto UI, hosted locally

Install the official prebuilt Perfetto UI once (requires internet and `curl`):

```sh
python3 tools/device-monitor/perfetto_local.py install
```

The installer pins version `v58.3-11fbaed83`, verifies the 76 manifest resource SHA-256 checksums, and stores unchanged upstream assets in the ignored `.cache/perfetto/` directory.
No Node build or Python dependencies are needed.
For upstream alternatives, see [Perfetto local development](https://perfetto.dev/docs/contributing/ui-getting-started) and [Android system tracing](https://perfetto.dev/docs/getting-started/system-tracing).
The UI and its WebAssembly trace processor run locally; normal trace viewing does not require uploading the trace.
Upstream links and optional online features can still contact external services.

Capture a native Android trace and open it locally:

```sh
python3 tools/device-monitor/perfetto_local.py record --duration 10 --output capture.pftrace
python3 tools/device-monitor/perfetto_local.py serve --trace capture.pftrace
```

Open the URL printed by the server (default port 10000).
The capture uses Android's built-in Perfetto command over ADB and requests scheduling, frequency, idle, graphics, and application categories.
Available tracks depend on the device, Android version, and permissions.
It writes a uniquely named trace on the phone, pulls the binary file separately from diagnostic output, and removes that temporary remote file.
Existing local output files are never overwritten.
Use `--serial SERIAL` or `--adb PATH` when needed.

To view our sampled CPU, memory, battery, and thermal measurements in the same real Perfetto UI:

```sh
python3 tools/device-monitor/dashboard.py session.ndjson
python3 tools/device-monitor/dashboard.py session.ndjson --summary
```

The dashboard command converts the chosen NDJSON session to Perfetto-compatible trace events and serves the installed UI on `127.0.0.1:8765`.
Open its printed URL; use `--port 8766` if needed.
The trace is a snapshot: reopen the printed URL to load new samples from an ongoing recording.
Perfetto does not continuously append our sampled data to an already-open timeline.
Unavailable values are represented by status events, never invented zero readings.
Counter tracks use Perfetto's step display between reported values; consult the availability events for missing measurements.
Native captures and sampled sessions are separate traces; automatic clock alignment and merging are not implemented.
Stop either local server with Ctrl+C.

To save a portable JSON trace without starting a server:

```sh
python3 tools/device-monitor/perfetto_export.py session.ndjson session-trace.json
```

## NPU inference and profiling

See [NPU.md](NPU.md) for the tested iQOO 15 / SM8850 workflow using Qwen3-0.6B, the local QAIRT SDK, and `genie_run.py`.
It captures token metrics and framework execution traces while keeping NPU utilization explicitly unsupported.

## AI-readable summaries

`--summary` excludes unavailable, unsupported, warming-up, and nonnumeric values.
For before-and-after comparisons, add `--compare AFTER_SESSION` to the dashboard command.

```sh
python3 tools/device-monitor/dashboard.py baseline.ndjson --compare after.ndjson
```

Summary means are unweighted sample averages, and comparisons retain run configuration and flag differences or missing numeric metrics.
Perfetto also provides its built-in SQL query interface for exploring native traces.
The tool supplies evidence for a coding AI; it does not change app code or call an AI service itself.

## Tests

The tests use parser fixtures and a temporary fake ADB executable, so they do not need a connected phone:

```sh
python3 -m unittest discover -s tools/device-monitor -p 'test_*.py' -v
```
