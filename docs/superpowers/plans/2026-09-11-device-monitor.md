# Android Device Monitor Implementation Plan

**Goal:** Build the approved Android-first monitoring tool with honest capability reporting and machine-readable sessions.

**Architecture:** A Python standard-library CLI discovers ADB devices and samples available system measurements.
A separate localhost dashboard reads session files, including sessions still being recorded.
Unavailable GPU/NPU counters remain explicit rather than becoming zero.

**Scope:** No root, APK changes, remote services, or third-party Python dependencies.
The initial deliverable samples system data; detailed Perfetto traces and manufacturer accelerator adapters are follow-up work.

## Implementation

- [x] Implement device discovery, explicit device selection, safe ADB calls, and capability probes in `tools/device-monitor/device_monitor.py`.
- [x] Implement timestamped NDJSON collection with units, sources, error states, and collection duration.
- [x] Test parsing, missing metrics, device-selection errors, and the CLI through a controlled ADB executable.
- [x] Implement `dashboard.py` to serve a local live view, summary, and comparison from the same session format.
- [x] Verify against the connected Samsung SM-A528B and inspect the dashboard in a real browser.
- [x] Document commands, measurement semantics, limitations, and actual verification evidence.

## Validation

Run `python3 -m unittest discover -s tools/device-monitor -p 'test_*.py' -v`.
Run discovery, probe, and a short real-device recording.
Verify unsupported metrics remain absent-valued and session output parses as JSON.
Check localhost dashboard rendering and session refresh.
This directory has no Git metadata, so commits and a Git diff are unavailable.
