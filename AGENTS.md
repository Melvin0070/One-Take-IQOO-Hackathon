# Agent context and profiling workflow

## Scope and intent

This workspace contains a Kotlin/Compose/CameraX video recorder and a standalone Android developer monitoring tool in `tools/device-monitor/`.
The long-term goal is to connect a phone, discover available performance signals, and give a coding agent evidence for improving an app.
The current implementation supports Android over ADB, not every phone platform.
Keep monitoring changes separate from recorder app changes unless the task requires both.

The user explicitly chose the actual Perfetto UI hosted locally after rejecting the custom dashboard.
Preserve that decision: use Perfetto's timeline and analysis tools rather than rebuilding its interface.
Our code owns capture, capability discovery, data conversion, and AI-readable summaries.
An automated AI optimization loop is not implemented.

## Start here

- Read `tools/device-monitor/README.md` for commands and metric semantics.
- Read `tools/device-monitor/VERIFICATION.md` for dated hardware evidence and remaining gaps.
- Read the root `README.md` before changing the recorder app.
- Check current files, connected devices, and running servers; do not assume a previous session's processes or hardware are still available.
- Run commands below from the repository root.

## Code map

| File | Responsibility |
| --- | --- |
| `tools/device-monitor/device_monitor.py` | ADB discovery, capability probing, sampled collectors, NDJSON recording |
| `tools/device-monitor/dashboard.py` | Session validation, summaries, comparisons, official UI serving, `/api/session`, generated `/trace` |
| `tools/device-monitor/perfetto_local.py` | Official UI installation, native Android trace capture, localhost trace serving |
| `tools/device-monitor/perfetto_export.py` | `convert_session(report)` and NDJSON to Chrome Trace Event JSON CLI |
| `tools/device-monitor/genie_run.py` | Bounded SM8850 QnnHtp LLM execution with raw Genie profiling |
| `tools/device-monitor/genie_trace.py` | Derived Perfetto lanes for crossing Genie slices without changing timings |
| `tools/device-monitor/test_*.py` | Parser, fake-ADB, export, capture, and HTTP regression coverage |

The monitoring tools use Python's standard library.
Capture requires ADB; downloading the UI requires `curl` and internet once.
The installed upstream UI and WebAssembly trace processor run locally.
Optional upstream links and online features can still contact external services.

## Efficient profiling loop

1. Define one reproducible action and a measurable hypothesis, such as CPU cost during caption generation.
2. Discover the device and probe its actual capabilities before choosing metrics.
3. Start with a short sampled baseline, usually 30 seconds at a one-second interval.
4. Read the JSON summary and coverage before opening the full timeline.
5. Capture a short native Perfetto trace when scheduling, CPU frequency, or process activity needs investigation.
6. Use Perfetto's timeline and SQL interface to investigate the relevant interval.
7. Make one focused code change, then repeat the same workload and compare recordings.
8. Report observed differences, repeatability, missing measurements, and validation evidence.

Keep the device, app build/configuration, workload, duration, interval, charging state, and starting thermal conditions comparable.
Repeat runs before attributing a small difference to a code change.
Aggregate device CPU includes other processes; it is not the app's CPU usage.
Use `--package` for app PSS memory, not per-app CPU attribution.
Avoid dumping complete traces or every sample into model context; prefer summaries and targeted query results.
Do not claim an optimization from one lower average or from a successful trace import alone.

## Commands

Discover and probe:

```sh
python3 tools/device-monitor/device_monitor.py devices
python3 tools/device-monitor/device_monitor.py probe
```

With multiple devices, pass `--serial SERIAL` to probe and recording commands.
ADB is discovered automatically; use `--adb PATH` when necessary.
Discover the current serial rather than hardcoding the previously tested phone.

Create a unique directory for each experiment, then record a baseline while reproducing the workload:

```sh
mkdir -p tools/device-monitor/runs
profile_run_dir=$(mktemp -d tools/device-monitor/runs/profile-XXXXXX)
python3 tools/device-monitor/device_monitor.py record \
  --duration 30 --interval 1 --package com.example.one_take \
  --output "$profile_run_dir/baseline.ndjson"
python3 tools/device-monitor/dashboard.py "$profile_run_dir/baseline.ndjson" --summary
```

Keep `profile_run_dir` in the same shell or replace it with the printed/saved directory path when using another terminal.
Output capture files are created exclusively; choose a new filename instead of overwriting evidence.
An app with no running process can legitimately have unavailable package memory.

Install the UI once, then view the sampled session:

```sh
python3 tools/device-monitor/perfetto_local.py install
python3 tools/device-monitor/dashboard.py "$profile_run_dir/baseline.ndjson"
```

Open the exact URL printed by the server, including its trace-loading hash.
The dashboard defaults to `127.0.0.1:8765`; pass `--port` if occupied.
The server runs in the foreground; use another terminal for capture or stop it with Ctrl+C.
An open Perfetto timeline is a snapshot, even though `/api/session` and `/trace` reread the source file.
Reopen the printed trace URL to load newly recorded samples.

For a native system trace:

```sh
python3 tools/device-monitor/perfetto_local.py record \
  --duration 10 --output "$profile_run_dir/native.pftrace"
python3 tools/device-monitor/perfetto_local.py serve \
  --trace "$profile_run_dir/native.pftrace"
```

The native UI server defaults to `127.0.0.1:10000`.
Native and sampled recordings are separate traces; automatic clock alignment and merging are not implemented.

After applying a change, reproduce the workload with the same recording options and save it as `after.ndjson`:

```sh
python3 tools/device-monitor/device_monitor.py record \
  --duration 30 --interval 1 --package com.example.one_take \
  --output "$profile_run_dir/after.ndjson"
python3 tools/device-monitor/dashboard.py "$profile_run_dir/baseline.ndjson" \
  --compare "$profile_run_dir/after.ndjson"
python3 tools/device-monitor/perfetto_export.py \
  "$profile_run_dir/baseline.ndjson" "$profile_run_dir/baseline-trace.json"
```

## Data integrity and known pitfalls

- Preserve `status`, `value`, `unit`, `source`, and `reason` for every metric.
- Never turn unsupported, unavailable, erroneous, or warming-up readings into zero.
- CPU utilization needs two `/proc/stat` readings; the first sample is normally warming up.
- Preserve vendor battery current as raw data when units are unknown; do not label it as amperes.
- Treat powered and charging as different states.
- Thermal sensor names and zero readings can be vendor-specific; do not invent a physical interpretation.
- Summary means are unweighted sample averages, excluding unavailable and nonnumeric readings.
- Collection overhead measures time spent gathering sequential readings, not incremental CPU load caused by the monitor.
- Chrome trace timestamps use elapsed seconds converted to microseconds; boolean states use numeric 0/1.
- Export missing readings as diagnostics and retain nonnumeric values in diagnostic arguments.
- Perfetto counter plots hold values between samples; consult status events rather than treating a flat segment as proof of continued measurement availability.

Do not capture native binary traces directly through `adb exec-out perfetto -o -`.
That path was observed to mix diagnostic text into the binary trace and make Perfetto reject it.
Keep the unique remote-file capture, separate binary pull, and best-effort cleanup of only the file created by that capture.

Keep upstream generated UI assets unchanged.
The pinned version is defined in `perfetto_local.py`; consult that constant rather than assuming a release is current.
Preserve manifest checksum checks, failed-install readiness invalidation, localhost binding, and static path confinement.
Do not disable TLS verification to work around certificate errors.
Keep `.cache/` and `runs/` out of version control and do not upload device traces without authorization.
Never read or modify `.env` files without asking first.

## Verification and completion

For monitoring code changes, run:

```sh
python3 -m unittest discover -s tools/device-monitor -p 'test_*.py' -v
```

For capture, export, or UI integration changes, also exercise the affected real flow and open its resulting trace in the local Perfetto UI when hardware is available.
Verify actual tracks and import errors; a successful command exit alone is insufficient.
Use the `chrome-devtools-axi` CLI for browser work and consult its current help.
Run the recorder's documented Android checks when recorder code changes, not for unrelated monitoring documentation edits.
Update `VERIFICATION.md` with actual commands, results, hardware, and remaining gaps after meaningful validation.
Do not treat historical test counts as current passing evidence.

As of 2026-09-11, validation covered a Samsung SM-A528B on Android 14 and macOS, including real sampled and native traces and 47 automated tests.
Other physical devices and host platforms remain unverified.
GPU/NPU utilization counters, thermal headroom, recorder app event instrumentation, and automatic AI-driven optimization remain future work.
On 2026-09-12, the iQOO 15 / SM8850 exposed eight type 9 NPU temperature sensors and successfully ran Qwen3-0.6B through QnnHtp with QAIRT 2.50.
Read `tools/device-monitor/NPU.md` before NPU work.
Genie traces describe framework/QNN-wrapper calls, not hardware occupancy.
Keep raw profiles unchanged; synthetic lanes in derived Perfetto traces do not represent OS threads or NPU cores.
Universal support means capability-aware adapters and honest missing data, not a promise that every phone exposes every counter.
