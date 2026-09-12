#!/usr/bin/env python3
"""View or summarize a Device Monitor NDJSON session using only Python stdlib."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import statistics
import sys
from urllib.parse import quote, unquote, urlsplit

try:
    import perfetto_local
except ImportError:  # pragma: no cover - used when imported as a package.
    from . import perfetto_local


PERFETTO_CACHE = perfetto_local.CACHE
PERFETTO_VERSION = perfetto_local.VERSION


def reject_constant(value):
    raise ValueError(f"Non-finite JSON number: {value}")


def validate_numbers(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            if not math.isfinite(value):
                raise ValueError("Non-finite numeric value")
        except OverflowError as exc:
            raise ValueError("Numeric value is too large") from exc
    elif isinstance(value, dict):
        for item in value.values():
            validate_numbers(item)
    elif isinstance(value, list):
        for item in value:
            validate_numbers(item)


def load_session(path):
    data = Path(path).read_bytes()
    lines = data.splitlines(keepends=True)
    result = {"session": {}, "samples": [], "completion": None,
              "partial_line": False}
    seen_session = False
    units = {}
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line, parse_constant=reject_constant)
        except (json.JSONDecodeError, UnicodeError) as exc:
            if number == len(lines) and not data.endswith(b"\n"):
                result["partial_line"] = True
                break
            raise ValueError(f"Invalid JSON on line {number}: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"Expected object on line {number}")
        validate_numbers(row)
        if result["completion"] is not None:
            raise ValueError(f"Record after completion on line {number}")
        kind = row.get("type")
        if kind == "session":
            if seen_session or result["samples"]:
                raise ValueError(f"Multiple or misplaced sessions on line {number}")
            if not isinstance(row.get("device", {}), dict):
                raise ValueError(f"Invalid device metadata on line {number}")
            seen_session = True
            result["session"] = row
        elif kind == "sample":
            metrics = row.get("metrics")
            if not isinstance(metrics, dict) or any(
                    not isinstance(metric, dict) for metric in metrics.values()):
                raise ValueError(f"Invalid metrics on line {number}")
            for name, metric in metrics.items():
                if not isinstance(metric.get("status"), str):
                    raise ValueError(f"Invalid metric status on line {number}")
                unit = metric.get("unit")
                if unit is not None and not isinstance(unit, str):
                    raise ValueError(f"Invalid metric unit on line {number}")
                if name in units and units[name] != unit:
                    raise ValueError(f"Inconsistent units for {name} on line {number}")
                units[name] = unit
            result["samples"].append(row)
        elif kind in ("summary", "end", "completion"):
            result["completion"] = row
        else:
            raise ValueError(f"Unknown record type on line {number}: {kind}")
    return result


def finite_mean(values):
    scale = max(abs(min(values)), abs(max(values)))
    return statistics.fmean(value / scale for value in values) * scale if scale else 0.0


def summarize(report):
    buckets = {}
    coverage = {}
    for sample in report["samples"]:
        for name, metric in sample["metrics"].items():
            entry = coverage.setdefault(name, {"statuses": {}, "source": None, "reason": None})
            status = metric.get("status", "unknown")
            entry["statuses"][status] = entry["statuses"].get(status, 0) + 1
            entry["source"], entry["reason"] = metric.get("source"), metric.get("reason")
            value = metric.get("value")
            if (metric.get("status") == "available"
                    and isinstance(value, (int, float)) and not isinstance(value, bool)
                    and math.isfinite(value)):
                key = (name, metric.get("unit"))
                buckets.setdefault(key, []).append(value)
    return {
        "device": report["session"].get("device", {}),
        "session": report["session"],
        "sample_count": len(report["samples"]),
        "completion": report["completion"],
        "coverage": coverage,
        "aggregation": "Unweighted mean of available samples; not a time-weighted average.",
        "metrics": {name: {"unit": unit, "count": len(values), "min": min(values),
                           "max": max(values), "mean": finite_mean(values)}
                    for (name, unit), values in buckets.items()},
    }


def compare(before, after):
    warnings = ["Descriptive comparison only. Use repeated runs with the same workload, "
                "device, sampling interval, and starting thermal conditions."]
    if before["device"] != after["device"]:
        warnings.append("Device metadata differs; these runs may not be comparable.")
    keys = ("package", "duration_s", "interval_s", "workload", "config", "configuration")
    if any(before.get("session", {}).get(key) != after.get("session", {}).get(key)
           for key in keys):
        warnings.append("Run configuration differs; inspect the retained session metadata.")
    metrics = {}
    missing = before["metrics"].keys() ^ after["metrics"].keys()
    if missing:
        warnings.append("Numeric metrics available in only one run: " + ", ".join(sorted(missing)))
    for name in sorted(before["metrics"].keys() & after["metrics"].keys()):
        left, right = before["metrics"][name], after["metrics"][name]
        if left["unit"] != right["unit"]:
            warnings.append(f"Skipped {name}: units differ.")
            continue
        delta = right["mean"] - left["mean"]
        metrics[name] = {"unit": left["unit"], "before_mean": left["mean"],
                         "after_mean": right["mean"], "mean_change": delta,
                         "mean_change_percent": delta / abs(left["mean"]) * 100
                         if left["mean"] else None}
    return {"before": before, "after": after, "metrics": metrics, "warnings": warnings}


def _write_response(handler, body, content_type, status=200):
    handler.send_response(status)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("X-Content-Type-Options", "nosniff")
    handler.end_headers()
    try:
        handler.wfile.write(body)
    except (BrokenPipeError, ConnectionResetError):
        pass


def _error_body(exc):
    return json.dumps({"error": str(exc)}, allow_nan=False).encode()


def handler_for(path):
    """Return the official Perfetto handler with the monitor API mounted beside it.

    Perfetto's static handler owns the complete upstream UI and its path
    traversal checks.  The monitor only intercepts the API and the generated
    trace endpoint, leaving all UI assets untouched.
    """

    root = Path(PERFETTO_CACHE) / PERFETTO_VERSION
    upstream_handler = perfetto_local.handler_for(root)

    class Handler(upstream_handler):
        def do_GET(self):
            route = unquote(urlsplit(self.path).path)
            if route == "/api/session":
                try:
                    report = load_session(path)
                    report["summary"] = summarize(report)
                    body = json.dumps(report, allow_nan=False).encode()
                except (OSError, ValueError) as exc:
                    _write_response(self, _error_body(exc), "application/json", 400)
                    return
                _write_response(self, body, "application/json")
                return
            if route == "/trace":
                try:
                    # Import lazily because perfetto_export imports the
                    # dashboard parser when used as a standalone CLI module.
                    try:
                        import perfetto_export
                    except ImportError:  # pragma: no cover - package import path.
                        from . import perfetto_export
                    trace = perfetto_export.convert_session(load_session(path))
                    body = json.dumps(trace, allow_nan=False).encode()
                except (OSError, ValueError, TypeError) as exc:
                    _write_response(self, _error_body(exc), "application/json", 400)
                    return
                _write_response(self, body, "application/json")
                return
            super().do_GET()

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--summary", action="store_true", help="Print scalar metric statistics as JSON")
    parser.add_argument("--compare", type=Path, metavar="AFTER_SESSION",
                        help="Compare this baseline session with a later session as JSON")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    try:
        report = load_session(args.session)
        if args.compare:
            print(json.dumps(compare(summarize(report), summarize(load_session(args.compare))),
                             indent=2, allow_nan=False))
            return 0
        if args.summary:
            print(json.dumps(summarize(report), indent=2, allow_nan=False))
            return 0
        perfetto_root = Path(PERFETTO_CACHE) / PERFETTO_VERSION
        if not (perfetto_root / ".ready").is_file():
            raise ValueError(
                "Perfetto UI is not installed. Run "
                "`python3 tools/device-monitor/perfetto_local.py install` once, "
                "then rerun the dashboard."
            )
        server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(args.session))
        address = f"http://127.0.0.1:{server.server_port}"
        trace_url = address + "/trace"
        perfetto_url = address + "/#!/?url=" + quote(trace_url, safe="")
        print(f"Perfetto UI: {perfetto_url}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
        return 0
    except (OSError, ValueError, OverflowError) as exc:
        print(f"device-dashboard: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
