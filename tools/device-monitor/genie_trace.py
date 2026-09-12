#!/usr/bin/env python3
"""Make a raw Genie profile safe to open as a Perfetto trace.

Genie's function trace can contain crossing synchronous ``X`` slices on one
thread.  Perfetto requires synchronous slices on a thread to be nested or
sequential, so this converter assigns crossing slices to deterministic visual
lanes.  It retains the profile statistics and every source event.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
from pathlib import Path
import sys
from typing import Any, Mapping


TRACE_FORMAT_VERSION = 1
ORIGINAL_PID_ARG = "original_pid"
ORIGINAL_TID_ARG = "original_tid"
CONVERSION_METADATA_KEY = "genie_trace_conversion"
SYNTHETIC_TID_START = 1_000_000


def _is_finite_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _validate_thread_id(value: Any, field: str) -> None:
    # Chrome trace process and thread identifiers are normally integers.  The
    # trace format also permits strings, so retain either scalar form exactly.
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise ValueError(f"Trace event {field} must be an integer or string")


def _thread_key(pid: Any, tid: Any) -> tuple[type, Any, type, Any]:
    return type(pid), pid, type(tid), tid


def _event_end(event: Mapping[str, Any]) -> tuple[int | float, int | float]:
    ts = event.get("ts")
    dur = event.get("dur")
    if not _is_finite_number(ts):
        raise ValueError("Genie X event ts must be a finite number")
    if not _is_finite_number(dur):
        raise ValueError("Genie X event dur must be a finite number")
    if dur < 0:
        raise ValueError("Genie X event dur must be nonnegative")
    return ts, ts + dur


def _copy_args_with_origin(event: Mapping[str, Any], pid: Any, tid: Any) -> dict[str, Any]:
    args = event.get("args", {})
    if not isinstance(args, Mapping):
        raise ValueError("Genie trace event args must be an object")
    copied = copy.deepcopy(dict(args))
    copied[ORIGINAL_PID_ARG] = pid
    copied[ORIGINAL_TID_ARG] = tid
    return copied


def _assign_lanes(slices: list[tuple[int, int | float, int | float]]) -> tuple[dict[int, int], int]:
    """Assign sorted ``(event_index, start, end)`` slices to nested lanes."""

    ordered = sorted(slices, key=lambda item: (item[1], -item[2], item[0]))
    stacks: list[list[int | float]] = []
    assignments: dict[int, int] = {}

    for event_index, start, end in ordered:
        chosen_lane: int | None = None
        for lane, stack in enumerate(stacks):
            while stack and stack[-1] <= start:
                stack.pop()
            if not stack or end <= stack[-1]:
                stack.append(end)
                chosen_lane = lane
                break
        if chosen_lane is None:
            stacks.append([end])
            chosen_lane = len(stacks) - 1
        assignments[event_index] = chosen_lane

    return assignments, max(1, len(stacks))


def _allocate_lane_tids(threads: list[dict[str, Any]]) -> None:
    """Keep lane zero's original tid and allocate collision-free extra tids."""

    used_by_pid: dict[Any, set[Any]] = {}
    for thread in threads:
        used_by_pid.setdefault(thread["pid"], set()).add(thread["tid"])

    next_by_pid: dict[Any, int] = {}
    for thread in threads:
        pid = thread["pid"]
        original_tid = thread["tid"]
        lane_tids = [original_tid]
        next_tid = next_by_pid.get(pid, SYNTHETIC_TID_START)
        for _ in range(1, thread["lane_count"]):
            while next_tid in used_by_pid[pid]:
                next_tid += 1
            lane_tids.append(next_tid)
            used_by_pid[pid].add(next_tid)
            next_tid += 1
        next_by_pid[pid] = next_tid
        thread["lane_tids"] = lane_tids


def _metadata_event(name: str, pid: Any, tid: Any, args: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "name": name,
        "cat": "__metadata",
        "ph": "M",
        "pid": pid,
        "tid": tid,
        "args": dict(args),
    }


def convert_profile(profile: Mapping[str, Any]) -> dict[str, Any]:
    """Return a Perfetto-safe derived trace for a loaded Genie profile.

    The source profile is deep-copied.  Its ``header``, ``metadata`` and
    ``components`` objects therefore remain unchanged, while the source
    ``traceEvents`` are copied into visual lanes.  Only ``X`` event ``tid``
    values are remapped; their original process and thread identifiers are
    retained in ``args.original_pid`` and ``args.original_tid``.
    """

    if not isinstance(profile, Mapping):
        raise ValueError("Genie profile must be a JSON object")
    source_events = profile.get("traceEvents")
    if not isinstance(source_events, list):
        raise ValueError("Genie profile traceEvents must be a list")

    events: list[Mapping[str, Any]] = []
    threads: list[dict[str, Any]] = []
    thread_by_key: dict[tuple[type, Any, type, Any], dict[str, Any]] = {}
    slices_by_thread: dict[tuple[type, Any, type, Any], list[tuple[int, int | float, int | float]]] = {}

    for event_index, source_event in enumerate(source_events):
        if not isinstance(source_event, Mapping):
            raise ValueError(f"Genie trace event {event_index} must be an object")
        event = copy.deepcopy(dict(source_event))
        events.append(event)

        pid = event.get("pid")
        tid = event.get("tid")
        has_thread = "pid" in event and "tid" in event
        if event.get("ph") == "X":
            if not has_thread:
                raise ValueError(f"Genie X event {event_index} must contain pid and tid")
            _validate_thread_id(pid, "pid")
            _validate_thread_id(tid, "tid")
            start, end = _event_end(event)
            key = _thread_key(pid, tid)
            thread = thread_by_key.get(key)
            if thread is None:
                thread = {"pid": pid, "tid": tid, "event_indices": [], "slices": []}
                thread_by_key[key] = thread
                threads.append(thread)
            thread["event_indices"].append(event_index)
            thread["slices"].append((event_index, start, end))
            continue

        # Raw Genie profiles currently contain only X events.  Preserve other
        # valid events and create a lane name for their original thread too.
        if has_thread:
            _validate_thread_id(pid, "pid")
            _validate_thread_id(tid, "tid")
            key = _thread_key(pid, tid)
            thread = thread_by_key.get(key)
            if thread is None:
                thread = {"pid": pid, "tid": tid, "event_indices": [], "slices": []}
                thread_by_key[key] = thread
                threads.append(thread)
            thread["event_indices"].append(event_index)

    for thread in threads:
        assignments, lane_count = _assign_lanes(thread["slices"])
        thread["assignments"] = assignments
        thread["lane_count"] = lane_count

    _allocate_lane_tids(threads)

    thread_event_to_lane: dict[int, int] = {}
    for thread in threads:
        for event_index, lane in thread["assignments"].items():
            thread_event_to_lane[event_index] = lane

    derived_events: list[dict[str, Any]] = []
    for event_index, event in enumerate(events):
        derived = copy.deepcopy(event)
        if event.get("ph") == "X":
            pid = event["pid"]
            tid = event["tid"]
            key = _thread_key(pid, tid)
            thread = thread_by_key[key]
            lane = thread_event_to_lane[event_index]
            derived["tid"] = thread["lane_tids"][lane]
            derived["args"] = _copy_args_with_origin(event, pid, tid)
        derived_events.append(derived)

    metadata_events: list[dict[str, Any]] = []
    lane_mapping: list[dict[str, Any]] = []
    sort_index = 0
    for thread in threads:
        lanes: list[dict[str, Any]] = []
        event_counts = [0] * thread["lane_count"]
        for event_index in thread["event_indices"]:
            event_counts[thread_event_to_lane.get(event_index, 0)] += 1
        for lane, output_tid in enumerate(thread["lane_tids"]):
            label = f"Genie thread {thread['tid']} [visual lane {lane}]"
            metadata_events.append(_metadata_event(
                "thread_name", thread["pid"], output_tid, {"name": label}))
            metadata_events.append(_metadata_event(
                "thread_sort_index", thread["pid"], output_tid, {"sort_index": sort_index}))
            lanes.append({
                "visual_lane": lane,
                "output_pid": thread["pid"],
                "output_tid": output_tid,
                "event_count": event_counts[lane],
            })
            sort_index += 1
        lane_mapping.append({
            "original_pid": thread["pid"],
            "original_tid": thread["tid"],
            "lanes": lanes,
        })

    conversion_metadata = {
        "format": "genie_profile_perfetto_safe",
        "format_version": TRACE_FORMAT_VERSION,
        "source_event_count": len(source_events),
        "output_event_count": len(source_events) + len(metadata_events),
        "slice_event_count": sum(1 for event in source_events if event.get("ph") == "X"),
        "metadata_event_count": len(metadata_events),
        "lane_mapping": lane_mapping,
    }

    result = copy.deepcopy(dict(profile))
    result["traceEvents"] = metadata_events + derived_events
    result["displayTimeUnit"] = "us"
    result[CONVERSION_METADATA_KEY] = conversion_metadata
    return result


def load_profile(path: Path | str) -> dict[str, Any]:
    source = Path(path)
    try:
        value = json.loads(source.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {source}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError("Genie profile must be a JSON object")
    return value


def export_file(input_path: Path | str, output_path: Path | str) -> dict[str, Any]:
    source = Path(input_path)
    destination = Path(output_path)
    if source.resolve() == destination.resolve():
        raise ValueError("Input and output must be distinct files")
    trace = convert_profile(load_profile(source))
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(trace, stream, indent=2, allow_nan=False)
        stream.write("\n")
    return trace


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, metavar="INPUT_PROFILE")
    parser.add_argument("output", type=Path, metavar="OUTPUT_TRACE")
    args = parser.parse_args(argv)
    try:
        export_file(args.input, args.output)
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
