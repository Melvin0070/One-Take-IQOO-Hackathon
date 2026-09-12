#!/usr/bin/env python3
"""Export a Device Monitor NDJSON session as Chrome Trace Event JSON.

The resulting JSON can be opened directly by the local Perfetto UI.  Numeric
and boolean metrics with ``status == "available"`` become counter (``C``)
events.  Other metric states remain visible as instant (``i``) diagnostics and
are never represented as a zero-valued counter.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
from typing import Any, Mapping

try:
    from dashboard import load_session, summarize
except ImportError:  # pragma: no cover - used when imported as a package.
    from .dashboard import load_session, summarize


TRACE_FORMAT_VERSION = 1
TRACE_CATEGORY = "android_device_monitor"
PROCESS_ID = 1
PROCESS_NAME = "Android Device Monitor"

# The IDs and labels are deliberately fixed so repeated exports retain the
# same process and group tracks, even when a metric is absent in some samples.
GROUPS: dict[str, tuple[int, str]] = {
    "cpu": (101, "CPU"),
    "memory": (102, "Memory"),
    "battery": (103, "Battery"),
    "thermal": (104, "Thermal"),
    "package": (105, "Package"),
    "gpu": (106, "GPU"),
    "npu": (107, "NPU"),
    "other": (108, "Other"),
}

# Keep the custom metadata small and predictable.  The complete source data is
# still represented by the timeline events and can be recovered from the
# original NDJSON session.
SESSION_METADATA_KEYS = (
    "schema_version",
    "session_id",
    "started_at",
    "started_monotonic_ns",
    "serial",
    "config",
)
DEVICE_METADATA_KEYS = (
    "manufacturer",
    "brand",
    "model",
    "device",
    "product",
    "android_release",
    "sdk",
    "abis",
    "hardware",
    "chipset",
    "soc_manufacturer",
    "gpu_driver",
    "security_patch",
)
SUMMARY_METADATA_KEYS = (
    "sample_count",
    "completion",
    "coverage",
    "aggregation",
    "metrics",
)


def load_report(path: Path | str) -> dict[str, Any]:
    """Load and validate a Device Monitor session through the dashboard loader."""

    return load_session(Path(path))


def _is_finite_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _counter_value(value: Any) -> int | float | None:
    if isinstance(value, bool):
        # Chrome Trace counter arguments are numeric.  Retain a boolean state
        # as the lossless 0/1 representation and keep its original unit in the
        # counter track label and metric metadata.
        return int(value)
    return value if _is_finite_number(value) else None


def _timestamp_us(elapsed_s: Any) -> int:
    if isinstance(elapsed_s, bool) or not isinstance(elapsed_s, (int, float)):
        raise ValueError("Sample elapsed_s must be a finite nonnegative number")
    try:
        if not math.isfinite(elapsed_s) or elapsed_s < 0:
            raise ValueError("Sample elapsed_s must be a finite nonnegative number")
        timestamp = elapsed_s * 1_000_000
        if not math.isfinite(timestamp):
            raise ValueError("Sample elapsed_s is too large for a trace timestamp")
    except OverflowError as exc:
        raise ValueError("Sample elapsed_s is too large for a trace timestamp") from exc
    return int(round(timestamp))


def _metric_group(metric_name: str) -> str:
    for group in ("cpu", "memory", "battery", "thermal", "package", "gpu", "npu"):
        if metric_name == group or metric_name.startswith(group + "_"):
            return group
    return "other"


def _metric_label(metric_name: str, unit: Any) -> str:
    if unit is None:
        unit_label = "unit unspecified"
    elif isinstance(unit, str) and unit:
        unit_label = unit
    else:
        unit_label = "unitless"
    return f"{metric_name} [{unit_label}]"


def _metadata_event(name: str, *, tid: int | None = None,
                    args: Mapping[str, Any]) -> dict[str, Any]:
    event: dict[str, Any] = {
        "name": name,
        "cat": "__metadata",
        "ph": "M",
        "pid": PROCESS_ID,
        "args": dict(args),
    }
    if tid is not None:
        event["tid"] = tid
    return event


def _instant_event(timestamp_us: int, group: str, name: str,
                   args: Mapping[str, Any]) -> dict[str, Any]:
    tid = GROUPS[group][0]
    return {
        "name": name,
        "cat": TRACE_CATEGORY,
        "ph": "i",
        "s": "t",
        "ts": timestamp_us,
        "pid": PROCESS_ID,
        "tid": tid,
        "args": dict(args),
    }


def _counter_event(timestamp_us: int, group: str, metric_name: str,
                   unit: Any, value: int | float) -> dict[str, Any]:
    tid = GROUPS[group][0]
    return {
        "name": GROUPS[group][1],
        "cat": TRACE_CATEGORY,
        "ph": "C",
        "ts": timestamp_us,
        "pid": PROCESS_ID,
        "tid": tid,
        "args": {_metric_label(metric_name, unit): value},
    }


def _selected(record: Mapping[str, Any], keys: tuple[str, ...]) -> dict[str, Any]:
    return {key: record[key] for key in keys if key in record}


def _metric_metadata(samples: list[Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    descriptors: dict[str, dict[str, Any]] = {}
    for sample in samples:
        metrics = sample.get("metrics")
        if not isinstance(metrics, Mapping):
            raise ValueError("Sample metrics must be an object")
        for metric_name in sorted(metrics):
            metric = metrics[metric_name]
            if not isinstance(metric_name, str) or not isinstance(metric, Mapping):
                raise ValueError("Metric names and values must be objects")
            status = metric.get("status")
            if not isinstance(status, str):
                raise ValueError(f"Invalid status for metric {metric_name}")
            unit = metric.get("unit")
            if unit is not None and not isinstance(unit, str):
                raise ValueError(f"Invalid unit for metric {metric_name}")
            descriptor = descriptors.setdefault(
                metric_name,
                {"unit": unit, "status_counts": {}, "sources": [], "reasons": []},
            )
            if descriptor["unit"] != unit:
                raise ValueError(f"Inconsistent units for {metric_name}")
            counts = descriptor["status_counts"]
            counts[status] = counts.get(status, 0) + 1
            for field in ("source", "reason"):
                value = metric.get(field)
                values = descriptor["sources" if field == "source" else "reasons"]
                if value not in values:
                    values.append(value)
    return {name: descriptors[name] for name in sorted(descriptors)}


def _metric_diagnostic(timestamp_us: int, metric_name: str,
                       metric: Mapping[str, Any]) -> dict[str, Any]:
    group = _metric_group(metric_name)
    args = {
        "metric": metric_name,
        "status": metric.get("status"),
        "unit": metric.get("unit"),
        "source": metric.get("source"),
        "reason": metric.get("reason"),
    }
    # An available nonnumeric value is retained as a diagnostic rather than
    # being coerced into a misleading counter sample.
    if metric.get("status") == "available" and _counter_value(metric.get("value")) is None:
        args["value_type"] = type(metric.get("value")).__name__
        args["value"] = metric.get("value")
    return _instant_event(timestamp_us, group, "metric_status", args)


def _missing_metric_diagnostic(timestamp_us: int, metric_name: str,
                               descriptor: Mapping[str, Any]) -> dict[str, Any]:
    return _metric_diagnostic(timestamp_us, metric_name, {
        "status": "unavailable",
        "unit": descriptor.get("unit"),
        "source": None,
        "reason": "Metric absent from sample",
    })


def convert_session(report: Mapping[str, Any]) -> dict[str, Any]:
    """Convert a validated ``dashboard.load_session`` report to Chrome JSON."""

    samples = report.get("samples")
    if not isinstance(samples, list):
        raise ValueError("Report samples must be a list")

    descriptors = _metric_metadata(samples)
    events: list[dict[str, Any]] = [
        _metadata_event("process_name", args={"name": PROCESS_NAME}),
        _metadata_event("process_sort_index", args={"sort_index": 0}),
    ]
    for sort_index, (group, (tid, label)) in enumerate(GROUPS.items()):
        events.append(_metadata_event("thread_name", tid=tid, args={"name": label}))
        events.append(_metadata_event("thread_sort_index", tid=tid,
                                      args={"sort_index": sort_index}))

    previous_elapsed: int | float | None = None
    for sample_number, sample in enumerate(samples, 1):
        if not isinstance(sample, Mapping):
            raise ValueError(f"Sample {sample_number} must be an object")
        elapsed_s = sample.get("elapsed_s")
        timestamp_us = _timestamp_us(elapsed_s)
        if previous_elapsed is not None and elapsed_s < previous_elapsed:
            raise ValueError("Sample elapsed_s values must be nondecreasing")
        previous_elapsed = elapsed_s
        metrics = sample.get("metrics")
        if not isinstance(metrics, Mapping):
            raise ValueError(f"Sample {sample_number} metrics must be an object")
        for metric_name in sorted(descriptors):
            if metric_name not in metrics:
                events.append(_missing_metric_diagnostic(
                    timestamp_us, metric_name, descriptors[metric_name]))
                continue
            metric = metrics[metric_name]
            if not isinstance(metric_name, str) or not isinstance(metric, Mapping):
                raise ValueError(f"Invalid metric in sample {sample_number}")
            status = metric.get("status")
            if not isinstance(status, str):
                raise ValueError(f"Invalid status for metric {metric_name}")
            unit = metric.get("unit")
            if unit is not None and not isinstance(unit, str):
                raise ValueError(f"Invalid unit for metric {metric_name}")
            value = _counter_value(metric.get("value"))
            if status == "available" and value is not None:
                events.append(_counter_event(
                    timestamp_us, _metric_group(metric_name), metric_name, unit, value))
            else:
                events.append(_metric_diagnostic(timestamp_us, metric_name, metric))

    session = report.get("session")
    if not isinstance(session, Mapping):
        session = {}
    device = session.get("device")
    if not isinstance(device, Mapping):
        device = {}
    completion = report.get("completion")
    summary_report = {
        "session": dict(session),
        "samples": samples,
        "completion": completion,
    }
    summary = summarize(summary_report)
    metadata = {
        "format": "android_device_monitor",
        "format_version": TRACE_FORMAT_VERSION,
        "session": _selected(session, SESSION_METADATA_KEYS),
        "device": _selected(device, DEVICE_METADATA_KEYS),
        "summary": _selected(summary, SUMMARY_METADATA_KEYS),
        "metrics": descriptors,
    }
    return {
        "traceEvents": events,
        "displayTimeUnit": "ms",
        "metadata": {"android_device_monitor": metadata},
    }


def build_trace(report: Mapping[str, Any]) -> dict[str, Any]:
    """Backward-compatible descriptive alias for :func:`convert_session`."""

    return convert_session(report)


def export_file(input_path: Path | str, output_path: Path | str) -> dict[str, Any]:
    """Convert two distinct session files and write the trace JSON file."""

    source = Path(input_path)
    destination = Path(output_path)
    if source.resolve() == destination.resolve():
        raise ValueError("Input and output must be distinct files")
    trace = convert_session(load_report(source))
    with destination.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(trace, stream, indent=2, allow_nan=False)
        stream.write("\n")
    return trace


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, metavar="INPUT_NDJSON")
    parser.add_argument("output", type=Path, metavar="OUTPUT_JSON")
    args = parser.parse_args(argv)
    try:
        export_file(args.input, args.output)
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
