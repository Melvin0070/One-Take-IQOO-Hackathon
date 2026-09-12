# Device Monitor

Build a standalone development-computer tool for Android phones connected through authorized ADB.
The first version requires no root and installs no software on the phone.
Python's standard library keeps setup independent of the recorder app and Android build.

## Collection

Discover connected devices and require an explicit serial when selection is ambiguous.
Probe actual accessible sources rather than assuming support from a manufacturer name.
Collect CPU counter deltas, memory, battery, thermal service readings, and optional selected-package memory.
Treat GPU and NPU utilization as unsupported until a compatible adapter exists.
Do not infer accelerator usage from the chipset name or infer exact chip temperatures from battery temperature.

Each metric includes a status, nullable value, unit, source, and reason.
Record timestamps and collection duration because ADB samples are sequential and introduce overhead.
CPU percentages represent activity during the counter interval, not the lifetime average printed by some diagnostic commands.

## Session format

Write newline-delimited JSON with one `session` metadata record, `sample` records, and a terminal `summary` record.
Keep unavailable measurements out of numeric aggregates.
Retain capability and failure information in exported summaries.
The dashboard tolerates a partially written final line while recording continues.

## Local dashboard and AI use

Serve only the selected session through a localhost HTTP server.
Show current readings, scalar trends, and metric coverage without external assets or services.
Export scalar summaries and descriptive before/after comparisons as JSON.
Means are unweighted sample means; comparisons do not establish that a code change caused an improvement.
Run repeated controlled workloads on the same device before accepting an optimization.

## Initial validation and extensions

Validate the real ADB flow on the connected Samsung SM-A528B running Android 14, plus controlled fixtures for unsupported sources and connection errors.
This establishes one-device evidence, not proof across every Android vendor.
Detailed Perfetto collection/analysis, in-app event markers, and vendor GPU/NPU integrations extend this foundation later.
iOS requires a separate transport and collector implementation.
