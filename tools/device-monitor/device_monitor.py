#!/usr/bin/env python3
"""Portable, read-only Android performance monitoring over ADB.

The command emits JSON for discovery and probing, and newline-delimited JSON
for recording sessions.  It deliberately reports unavailable vendor counters
as unavailable instead of inventing values.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid
from typing import Any, Callable, Mapping, Optional, Sequence, TextIO


SCHEMA_VERSION = 1
DEFAULT_TIMEOUT_S = 5.0
DEFAULT_DURATION_S = 30.0
DEFAULT_INTERVAL_S = 1.0
PROBE_CPU_WINDOW_S = 0.1

SOURCE_CPU = "/proc/stat delta"
SOURCE_MEMORY = "/proc/meminfo"
SOURCE_BATTERY = "adb shell dumpsys battery"
SOURCE_THERMAL = "adb shell dumpsys thermalservice"
SOURCE_GPU = "portable Android collector"
SOURCE_NPU = "portable Android collector"

# AOSP reserves these thermal types for battery current/voltage/percentage
# values.  They are reported in the thermal dump but are not temperatures.
THERMAL_NON_TEMPERATURE_TYPES = frozenset({6, 7, 8})
THERMAL_NPU_TYPE = 9


class MonitorError(Exception):
    """An expected CLI failure that can be represented as JSON."""

    def __init__(self, message: str, code: str = "monitor_error") -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class UsageError(MonitorError):
    def __init__(self, message: str) -> None:
        super().__init__(message, "usage_error")


class AdbNotFoundError(MonitorError):
    def __init__(self, message: str) -> None:
        super().__init__(message, "adb_not_found")


class AdbCommandError(MonitorError):
    """A failed or timed-out ADB invocation."""

    def __init__(
        self,
        command: Sequence[str],
        message: str,
        *,
        returncode: Optional[int] = None,
        timed_out: bool = False,
    ) -> None:
        self.command = tuple(command)
        self.returncode = returncode
        self.timed_out = timed_out
        self.disconnected = _looks_disconnected(message)
        code = "device_disconnected" if self.disconnected else "adb_command_failed"
        super().__init__(message, code)


class DeviceSelectionError(MonitorError):
    def __init__(self, message: str, code: str = "device_selection") -> None:
        super().__init__(message, code)


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    attributes: dict[str, str]

    def as_dict(self) -> dict[str, Any]:
        return {
            "serial": self.serial,
            "state": self.state,
            "attributes": dict(self.attributes),
        }


@dataclass(frozen=True)
class AdbClient:
    """Small argv-only ADB client.

    Every remote operation is passed as separate arguments.  In particular,
    package names are never interpolated into a shell command string.
    """

    adb_path: str
    serial: Optional[str] = None
    timeout_s: float = DEFAULT_TIMEOUT_S

    def run(self, *args: str, timeout_s: Optional[float] = None) -> str:
        command = [self.adb_path]
        if self.serial is not None:
            command.extend(("-s", self.serial))
        command.extend(args)
        timeout = self.timeout_s if timeout_s is None else timeout_s
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
                shell=False,
            )
        except FileNotFoundError as exc:
            raise AdbNotFoundError(f"ADB executable was not found: {self.adb_path}") from exc
        except PermissionError as exc:
            raise AdbNotFoundError(f"ADB executable is not executable: {self.adb_path}") from exc
        except subprocess.TimeoutExpired as exc:
            rendered = shlex.join(command)
            raise AdbCommandError(
                command,
                f"ADB command timed out after {timeout:g}s: {rendered}",
                timed_out=True,
            ) from exc
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "command failed").strip()
            rendered = shlex.join(command)
            raise AdbCommandError(
                command,
                f"ADB command failed with exit code {result.returncode}: {rendered}: {detail}",
                returncode=result.returncode,
            )
        return result.stdout

    def shell(self, *args: str, timeout_s: Optional[float] = None) -> str:
        return self.run("shell", *args, timeout_s=timeout_s)

    def devices(self) -> list[Device]:
        return parse_devices(self.run("devices", "-l"))


def _looks_disconnected(message: str) -> bool:
    lowered = message.lower()
    return ("device" in lowered and "not found" in lowered) or any(
        marker in lowered
        for marker in (
            "no devices/emulators found",
            "device offline",
            "device unauthorized",
            "error: closed",
            "transport error",
            "cannot connect",
            "connection reset",
        )
    )


def _path_is_executable(path: Path) -> bool:
    return path.is_file() and (os.access(path, os.X_OK) or path.suffix.lower() == ".exe")


def discover_adb(
    explicit: Optional[str] = None,
    *,
    environment: Optional[Mapping[str, str]] = None,
    path_lookup: Optional[Callable[[str], Optional[str]]] = None,
    home: Optional[Path] = None,
) -> str:
    """Find an ADB executable from an explicit path, SDK roots, or PATH."""

    env = os.environ if environment is None else environment
    lookup = shutil.which if path_lookup is None else path_lookup
    home_path = Path.home() if home is None else Path(home)

    candidates: list[Path] = []
    if explicit:
        explicit_path = Path(explicit).expanduser()
        if explicit_path.is_absolute() or explicit_path.parent != Path("."):
            candidates.append(explicit_path)
        else:
            resolved = lookup(explicit)
            if resolved:
                candidates.append(Path(resolved))
            candidates.append(explicit_path)
        seen_explicit: set[str] = set()
        for candidate in candidates:
            key = str(candidate)
            if key in seen_explicit:
                continue
            seen_explicit.add(key)
            if _path_is_executable(candidate):
                return str(candidate.resolve())
        raise AdbNotFoundError(
            f"ADB executable {explicit!r} was not found or is not executable."
        )
    if env.get("ADB"):
        candidates.append(Path(env["ADB"]).expanduser())
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = env.get(variable)
        if sdk_root:
            candidates.extend(
                Path(sdk_root).expanduser() / "platform-tools" / name
                for name in ("adb", "adb.exe")
            )
    path_adb = lookup("adb")
    if path_adb:
        candidates.append(Path(path_adb))
    for sdk_root in (
        home_path / "Library" / "Android" / "sdk",
        home_path / "Android" / "Sdk",
    ):
        candidates.extend(
            sdk_root / "platform-tools" / name for name in ("adb", "adb.exe")
        )

    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate)
        if key in seen:
            continue
        seen.add(key)
        if _path_is_executable(candidate):
            return str(candidate.resolve())

    requested = f" for {explicit!r}" if explicit else ""
    raise AdbNotFoundError(
        "Could not find an executable ADB" + requested + ". "
        "Install Android platform-tools, put adb on PATH, or set ANDROID_HOME/ANDROID_SDK_ROOT."
    )


def parse_devices(text: str) -> list[Device]:
    """Parse ``adb devices -l`` while retaining non-ready states."""

    devices: list[Device] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.lower().startswith("list of devices attached"):
            continue
        fields = line.split()
        if len(fields) < 2:
            continue
        serial, state = fields[0], fields[1]
        attribute_start = 2
        if state == "no" and len(fields) > 2 and fields[2] == "permissions":
            state = "no permissions"
            attribute_start = 3
        attributes: dict[str, str] = {}
        for field in fields[attribute_start:]:
            if ":" not in field:
                continue
            key, value = field.split(":", 1)
            if key:
                attributes[key] = value
        devices.append(Device(serial, state, attributes))
    return devices


def select_device(devices: Sequence[Device], serial: Optional[str] = None) -> Device:
    if serial is not None:
        if not serial:
            raise DeviceSelectionError("--serial cannot be empty", "invalid_serial")
        selected = next((device for device in devices if device.serial == serial), None)
        if selected is None:
            available = ", ".join(device.serial for device in devices) or "none"
            raise DeviceSelectionError(
                f"No connected device has serial {serial!r}; available devices: {available}",
                "unknown_serial",
            )
        if selected.state == "unauthorized":
            raise DeviceSelectionError(
                f"Device {serial!r} is unauthorized. Unlock the phone and accept the USB debugging prompt.",
                "unauthorized",
            )
        if selected.state != "device":
            raise DeviceSelectionError(
                f"Device {serial!r} is {selected.state!r}, not ready for ADB commands.",
                "device_not_ready",
            )
        return selected

    ready = [device for device in devices if device.state == "device"]
    if len(ready) > 1:
        serials = ", ".join(device.serial for device in ready)
        raise DeviceSelectionError(
            f"multiple devices are connected ({serials}); choose one with --serial.",
            "multiple_devices",
        )
    if ready:
        return ready[0]
    if not devices:
        raise DeviceSelectionError(
            "No Android devices are connected. Enable USB debugging and reconnect the phone.",
            "no_devices",
        )
    unauthorized = [device.serial for device in devices if device.state == "unauthorized"]
    if unauthorized:
        raise DeviceSelectionError(
            "Connected device is unauthorized. Unlock the phone and accept the USB debugging prompt.",
            "unauthorized",
        )
    states = ", ".join(f"{device.serial}={device.state}" for device in devices)
    raise DeviceSelectionError(
        f"No connected device is ready for ADB commands ({states}).",
        "device_not_ready",
    )


_GETPROP_RE = re.compile(r"^\[([^\]]+)\]: \[(.*)\]$")


def parse_getprop(text: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in text.splitlines():
        match = _GETPROP_RE.match(line.strip())
        if match:
            properties[match.group(1)] = match.group(2)
    return properties


def _int_or_none(value: Optional[str]) -> Optional[int]:
    if value is None:
        return None
    try:
        return int(value, 0)
    except (TypeError, ValueError):
        return None


def build_device_info(properties: Mapping[str, str], serial: Optional[str] = None) -> dict[str, Any]:
    sdk = _int_or_none(properties.get("ro.build.version.sdk"))
    abi_values = properties.get("ro.product.cpu.abilist", "")
    abis = [abi.strip() for abi in abi_values.split(",") if abi.strip()]
    return {
        "serial": serial,
        "manufacturer": properties.get("ro.product.manufacturer"),
        "brand": properties.get("ro.product.brand"),
        "model": properties.get("ro.product.model"),
        "device": properties.get("ro.product.device"),
        "product": properties.get("ro.product.name"),
        "android_release": properties.get("ro.build.version.release"),
        "sdk": sdk,
        "abis": abis,
        "hardware": properties.get("ro.hardware"),
        "chipset": properties.get("ro.hardware.chipname") or properties.get("ro.soc.model"),
        "soc_manufacturer": properties.get("ro.soc.manufacturer"),
        "gpu_driver": properties.get("ro.hardware.egl"),
        "fingerprint": properties.get("ro.build.fingerprint"),
        "security_patch": properties.get("ro.build.version.security_patch"),
    }


def read_device_info(client: AdbClient) -> dict[str, Any]:
    return build_device_info(parse_getprop(client.shell("getprop")), client.serial)


def parse_proc_stat(text: str) -> dict[str, dict[str, Any]]:
    """Return Linux CPU counters keyed by aggregate/per-core CPU name."""

    counters: dict[str, dict[str, Any]] = {}
    for line in text.splitlines():
        fields = line.split()
        if not fields or (fields[0] != "cpu" and not re.fullmatch(r"cpu\d+", fields[0])):
            continue
        try:
            values = [int(value) for value in fields[1:]]
        except ValueError:
            continue
        if len(values) < 4:
            continue
        if any(value < 0 for value in values):
            continue
        # guest and guest_nice are already included in user and nice on Linux.
        # Summing the first eight counters avoids double-counting them.
        total = sum(values[:8])
        idle = values[3] + (values[4] if len(values) > 4 else 0)
        counters[fields[0]] = {"total": total, "idle": idle, "fields": values}
    return counters


def cpu_utilization(before: Mapping[str, Any], after: Mapping[str, Any]) -> Optional[float]:
    try:
        total_delta = int(after["total"]) - int(before["total"])
        idle_delta = int(after["idle"]) - int(before["idle"])
    except (KeyError, TypeError, ValueError):
        return None
    if total_delta <= 0 or idle_delta < 0 or idle_delta > total_delta:
        return None
    busy_delta = total_delta - idle_delta
    return busy_delta / total_delta * 100.0


def make_metric(
    status: str,
    value: Any = None,
    unit: Optional[str] = None,
    source: Optional[str] = None,
    reason: Optional[str] = None,
) -> dict[str, Any]:
    return {
        "status": status,
        "value": value,
        "unit": unit,
        "source": source,
        "reason": reason,
    }


class CpuSampler:
    def __init__(self, client: AdbClient) -> None:
        self.client = client
        self.previous: Optional[dict[str, Any]] = None
        self.last_error: Optional[AdbCommandError] = None

    def sample(self) -> dict[str, Any]:
        self.last_error = None
        try:
            counters = parse_proc_stat(self.client.shell("cat", "/proc/stat"))
        except AdbCommandError as exc:
            self.last_error = exc
            return make_metric(
                "error",
                unit="%",
                source=SOURCE_CPU,
                reason=str(exc),
            )
        current = counters.get("cpu")
        if current is None:
            return make_metric(
                "unavailable",
                unit="%",
                source=SOURCE_CPU,
                reason="The device did not expose a valid aggregate /proc/stat counter.",
            )
        if self.previous is None:
            self.previous = current
            return make_metric(
                "warming_up",
                unit="%",
                source=SOURCE_CPU,
                reason="CPU utilization needs two /proc/stat readings.",
            )
        value = cpu_utilization(self.previous, current)
        self.previous = current
        if value is None or not math.isfinite(value):
            return make_metric(
                "unavailable",
                unit="%",
                source=SOURCE_CPU,
                reason="CPU counters were reset or had no positive interval.",
            )
        return make_metric("available", value, "%", SOURCE_CPU)


def parse_meminfo(text: str) -> dict[str, int]:
    values: dict[str, int] = {}
    unit_multipliers = {
        "kb": 1024,
        "mb": 1024 * 1024,
        "gb": 1024 * 1024 * 1024,
        "b": 1,
    }
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        fields = raw.strip().split()
        if not fields:
            continue
        try:
            number = int(fields[0])
        except ValueError:
            continue
        multiplier = unit_multipliers.get(fields[1].lower(), 1) if len(fields) > 1 else 1
        values[key.strip()] = number * multiplier
    return values


def memory_metrics(values: Mapping[str, int]) -> dict[str, dict[str, Any]]:
    total = values.get("MemTotal")
    available = values.get("MemAvailable")
    metrics: dict[str, dict[str, Any]] = {}
    metrics["memory_total"] = (
        make_metric("available", total, "bytes", SOURCE_MEMORY)
        if total is not None and total >= 0
        else make_metric("unavailable", unit="bytes", source=SOURCE_MEMORY, reason="MemTotal is missing.")
    )
    metrics["memory_available"] = (
        make_metric("available", available, "bytes", SOURCE_MEMORY)
        if available is not None and available >= 0
        else make_metric(
            "unavailable",
            unit="bytes",
            source=SOURCE_MEMORY,
            reason="MemAvailable is missing; no substitute value was assumed.",
        )
    )
    if total is not None and available is not None and total > 0 and total >= available >= 0:
        used = total - available
        metrics["memory_used"] = make_metric("available", used, "bytes", SOURCE_MEMORY)
        metrics["memory_used_percent"] = make_metric(
            "available", used / total * 100, "%", SOURCE_MEMORY
        )
    else:
        metrics["memory_used"] = make_metric(
            "unavailable", unit="bytes", source=SOURCE_MEMORY, reason="Total and available memory are incomplete."
        )
        metrics["memory_used_percent"] = make_metric(
            "unavailable", unit="%", source=SOURCE_MEMORY, reason="Total and available memory are incomplete."
        )
    return metrics


def parse_battery(text: str) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, raw_value = line.split(":", 1)
        key = key.strip().lower().replace("_", " ")
        raw_value = raw_value.strip()
        if key in {"ac powered", "usb powered", "wireless powered", "charging"}:
            if raw_value.lower() in {"true", "false"}:
                values[key] = raw_value.lower() == "true"
            continue
        if key in {"status", "health", "level", "scale", "temperature", "voltage"}:
            try:
                values[key] = int(raw_value, 0)
            except ValueError:
                continue
            continue
        if key in {"current now", "current average", "current"}:
            match = re.search(r"([-+]?\d+(?:\.\d+)?)\s*([A-Za-zµμ]+)?", raw_value)
            if match:
                raw_number = match.group(1)
                values["current_raw"] = float(raw_number) if "." in raw_number else int(raw_number)
                values["current_unit"] = (match.group(2) or "").lower() or None
    power_keys = ("ac powered", "usb powered", "wireless powered")
    if any(key in values for key in power_keys):
        values["powered"] = any(bool(values.get(key)) for key in power_keys)
    if "charging" not in values and "status" in values:
        # BatteryManager.BATTERY_STATUS_CHARGING is 2.  A full battery can
        # remain plugged in without actively charging.
        values["charging"] = values["status"] == 2
    if "temperature" in values:
        values["temperature_c"] = values["temperature"] / 10.0
    if "voltage" in values:
        values["voltage_mv"] = values["voltage"]
    return values


def battery_metrics(values: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    source = SOURCE_BATTERY
    level = values.get("level")
    scale = values.get("scale")
    if isinstance(level, int) and isinstance(scale, int) and scale > 0:
        level_metric = make_metric("available", level / scale * 100, "%", source)
    else:
        level_metric = make_metric(
            "unavailable", unit="%", source=source, reason="Battery level or scale is not exposed."
        )
    metrics = {
        "battery_level": level_metric,
        "battery_temperature": (
            make_metric("available", values["temperature_c"], "C", source)
            if "temperature_c" in values
            else make_metric("unavailable", unit="C", source=source, reason="Battery temperature is not exposed.")
        ),
        "battery_voltage": (
            make_metric("available", values["voltage_mv"], "mV", source)
            if "voltage_mv" in values
            else make_metric("unavailable", unit="mV", source=source, reason="Battery voltage is not exposed.")
        ),
        "battery_current": make_metric(
            "unavailable",
            unit="mA",
            source=source,
            reason=(
                "The vendor current value has no documented unit."
                if "current_raw" in values and not values.get("current_unit")
                else "Battery current is not exposed."
            ),
        ),
        "battery_charging": (
            make_metric("available", bool(values["charging"]), "state", source)
            if "charging" in values
            else make_metric("unavailable", unit="state", source=source, reason="Charging state is not exposed.")
        ),
        "battery_powered": (
            make_metric("available", bool(values["powered"]), "state", source)
            if "powered" in values
            else make_metric("unavailable", unit="state", source=source, reason="Power source state is not exposed.")
        ),
    }
    if "status" in values:
        metrics["battery_status"] = make_metric("available", values["status"], "code", source)
    else:
        metrics["battery_status"] = make_metric(
            "unavailable", unit="code", source=source, reason="Battery status is not exposed."
        )
    if "current_raw" in values:
        raw_unit = values.get("current_unit")
        metrics["battery_current_raw"] = make_metric(
            "available", values["current_raw"], raw_unit or "vendor_raw", source
        )
        if raw_unit in {"ua", "μa", "µa"}:
            metrics["battery_current"] = make_metric(
                "available", float(values["current_raw"]) / 1000, "mA", source
            )
        elif raw_unit == "ma":
            metrics["battery_current"] = make_metric("available", values["current_raw"], "mA", source)
        elif raw_unit == "a":
            metrics["battery_current"] = make_metric(
                "available", float(values["current_raw"]) * 1000, "mA", source
            )
    return metrics


_THERMAL_STOP_MARKERS = (
    "temperature static thresholds",
    "temperature thresholds",
    "cooling devices",
    "current cooling devices",
    "thermal listeners",
    "thermal callbacks",
)


def parse_thermalservice(text: str) -> dict[str, Any]:
    """Parse current thermal values and stop before cached threshold sections."""

    lines = text.splitlines()
    header_lines: list[str] = []
    current_lines: list[str] = []
    in_current_section = False
    in_cached_section = False
    for line in lines:
        lowered = line.strip().lower()
        if any(lowered.startswith(marker) for marker in _THERMAL_STOP_MARKERS):
            break
        header_lines.append(line)
        if lowered.startswith("cached temperatures"):
            in_cached_section = True
            continue
        if lowered.startswith("current temperatures from hal"):
            in_current_section = True
            in_cached_section = False
            continue
        if in_current_section:
            current_lines.append(line)
        elif not in_cached_section:
            current_lines.append(line)
    status_text = "\n".join(header_lines)
    status_match = re.search(
        r"(?:current\s+)?thermal\s+status\s*:\s*(-?\d+)",
        status_text,
        re.IGNORECASE,
    )
    status = int(status_match.group(1)) if status_match else None
    sensors: dict[str, dict[str, Any]] = {}
    sensor_text = "\n".join(current_lines)
    for object_match in re.finditer(
        r"Temperature\{(.*?)\}", sensor_text, re.IGNORECASE | re.DOTALL
    ):
        body = object_match.group(1)
        value_match = re.search(r"mValue\s*=\s*([-+]?\d+(?:\.\d+)?)", body, re.IGNORECASE)
        type_match = re.search(r"mType\s*=\s*(-?\d+)", body, re.IGNORECASE)
        name_match = re.search(r"mName\s*=\s*([^,}]+)", body, re.IGNORECASE)
        sensor_status_match = re.search(r"mStatus\s*=\s*(-?\d+)", body, re.IGNORECASE)
        if not value_match or not name_match:
            continue
        try:
            value = float(value_match.group(1))
        except ValueError:
            continue
        name = name_match.group(1).strip()
        if not name:
            continue
        sensors[name] = {
            "value_c": value,
            "type": int(type_match.group(1)) if type_match else None,
            "status": int(sensor_status_match.group(1)) if sensor_status_match else None,
        }
    return {"status": status, "sensors": sensors}


def _sensor_slug(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return slug or "unknown"


def thermal_metrics(parsed: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    metrics: dict[str, dict[str, Any]] = {}
    status = parsed.get("status")
    if isinstance(status, int):
        metrics["thermal_status"] = make_metric("available", status, "severity", SOURCE_THERMAL)
    else:
        metrics["thermal_status"] = make_metric(
            "unavailable", unit="severity", source=SOURCE_THERMAL, reason="Current thermal status is not exposed."
        )
    used_names: set[str] = set()
    for sensor_name, sensor in (parsed.get("sensors") or {}).items():
        raw_sensor_type = sensor.get("type") if isinstance(sensor, Mapping) else None
        try:
            sensor_type = int(raw_sensor_type) if raw_sensor_type is not None else None
        except (TypeError, ValueError):
            sensor_type = None
        if sensor_type in THERMAL_NON_TEMPERATURE_TYPES:
            continue
        prefix = "npu_temperature" if sensor_type == THERMAL_NPU_TYPE else "thermal_sensor"
        key = f"{prefix}_{_sensor_slug(str(sensor_name))}"
        suffix = 2
        base = key
        while key in used_names:
            key = f"{base}_{suffix}"
            suffix += 1
        used_names.add(key)
        value = sensor.get("value_c") if isinstance(sensor, Mapping) else None
        if isinstance(value, (int, float)) and math.isfinite(value):
            metrics[key] = make_metric("available", value, "C", SOURCE_THERMAL)
        else:
            metrics[key] = make_metric(
                "unavailable", unit="C", source=SOURCE_THERMAL, reason="Sensor value is malformed."
            )
    return metrics


def parse_package_meminfo(text: str) -> Optional[int]:
    """Return the package's TOTAL PSS in bytes when dumpsys exposes it."""

    for line in text.splitlines():
        match = re.match(r"^\s*TOTAL(?:\s+|:)(.*)$", line, re.IGNORECASE)
        if not match:
            continue
        number_match = re.search(r"\b(\d+)\b", match.group(1))
        if number_match:
            return int(number_match.group(1)) * 1024
    return None


_PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


def validate_package(package: str) -> str:
    if not isinstance(package, str) or not _PACKAGE_RE.fullmatch(package):
        raise ValueError(
            "package must look like an Android package name such as com.example.app; shell syntax is rejected"
        )
    return package


def unsupported_accelerator_metrics() -> dict[str, dict[str, Any]]:
    return {
        "gpu_utilization": make_metric(
            "unsupported",
            unit="%",
            source=SOURCE_GPU,
            reason="Portable Android APIs do not expose GPU utilization across devices.",
        ),
        "npu_utilization": make_metric(
            "unsupported",
            unit="%",
            source=SOURCE_NPU,
            reason="NPU utilization requires a vendor-specific profiler or backend integration.",
        ),
    }


def _error_record(exc: AdbCommandError) -> dict[str, Any]:
    return {
        "code": exc.code,
        "message": str(exc),
        "command": list(exc.command),
    }


def _failed_metrics(
    names_and_units: Sequence[tuple[str, str]],
    exc: AdbCommandError,
    source: str,
) -> dict[str, dict[str, Any]]:
    return {
        name: make_metric("error", unit=unit, source=source, reason=str(exc))
        for name, unit in names_and_units
    }


def collect_sample(
    client: AdbClient,
    cpu_sampler: CpuSampler,
    package: Optional[str] = None,
    *,
    clock: Callable[[], int] = time.monotonic_ns,
) -> dict[str, Any]:
    started_ns = clock()
    metrics: dict[str, dict[str, Any]] = {}
    errors: list[dict[str, Any]] = []

    cpu_metric = cpu_sampler.sample()
    metrics["cpu_utilization"] = cpu_metric
    if cpu_sampler.last_error is not None:
        errors.append(_error_record(cpu_sampler.last_error))

    try:
        metrics.update(memory_metrics(parse_meminfo(client.shell("cat", "/proc/meminfo"))))
    except AdbCommandError as exc:
        metrics.update(
            _failed_metrics(
                (
                    ("memory_total", "bytes"),
                    ("memory_available", "bytes"),
                    ("memory_used", "bytes"),
                    ("memory_used_percent", "%"),
                ),
                exc,
                SOURCE_MEMORY,
            )
        )
        errors.append(_error_record(exc))

    try:
        metrics.update(battery_metrics(parse_battery(client.shell("dumpsys", "battery"))))
    except AdbCommandError as exc:
        metrics.update(
            _failed_metrics(
                (
                    ("battery_level", "%"),
                    ("battery_temperature", "C"),
                    ("battery_voltage", "mV"),
                    ("battery_current", "mA"),
                    ("battery_charging", "state"),
                    ("battery_powered", "state"),
                    ("battery_status", "code"),
                ),
                exc,
                SOURCE_BATTERY,
            )
        )
        errors.append(_error_record(exc))

    try:
        metrics.update(thermal_metrics(parse_thermalservice(client.shell("dumpsys", "thermalservice"))))
    except AdbCommandError as exc:
        metrics.update(_failed_metrics((("thermal_status", "severity"),), exc, SOURCE_THERMAL))
        errors.append(_error_record(exc))

    if package is not None:
        package = validate_package(package)
        source = "adb shell dumpsys meminfo <package>"
        try:
            value = parse_package_meminfo(client.shell("dumpsys", "meminfo", package))
            metrics["package_memory"] = (
                make_metric("available", value, "bytes", source)
                if value is not None
                else make_metric(
                    "unavailable",
                    unit="bytes",
                    source=source,
                    reason="dumpsys meminfo did not expose a TOTAL PSS row for this package.",
                )
            )
        except AdbCommandError as exc:
            metrics["package_memory"] = make_metric("error", unit="bytes", source=source, reason=str(exc))
            errors.append(_error_record(exc))

    metrics.update(unsupported_accelerator_metrics())
    finished_ns = clock()
    return {
        "metrics": metrics,
        "errors": errors,
        "collection_ms": (finished_ns - started_ns) / 1_000_000,
        "finished_ns": finished_ns,
    }


def _capability_status(metrics: Mapping[str, Mapping[str, Any]], names: Sequence[str]) -> str:
    statuses = [metrics[name].get("status") for name in names if name in metrics]
    if "available" in statuses:
        return "available"
    if "warming_up" in statuses:
        return "warming_up"
    if statuses and all(status == "unsupported" for status in statuses):
        return "unsupported"
    if statuses:
        return "unavailable"
    return "unknown"


def build_capabilities(metrics: Mapping[str, Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    groups = {
        "cpu": ("cpu_utilization", SOURCE_CPU),
        "memory": ("memory_total", SOURCE_MEMORY),
        "battery": ("battery_level", SOURCE_BATTERY),
        "thermal": ("thermal_status", SOURCE_THERMAL),
        "gpu": ("gpu_utilization", SOURCE_GPU),
        "npu": ("npu_utilization", SOURCE_NPU),
    }
    result: dict[str, dict[str, Any]] = {}
    for name, (metric_name, source) in groups.items():
        metric = metrics.get(metric_name, {})
        status = _capability_status(metrics, (metric_name,))
        result[name] = {
            "status": status,
            "metric": metric_name,
            "source": source,
            "reason": metric.get("reason"),
        }
    npu_temperature_names = sorted(
        name for name in metrics if name.startswith("npu_temperature_")
    )
    npu_temperature_status = _capability_status(metrics, npu_temperature_names)
    result["npu_temperature"] = {
        "status": npu_temperature_status if npu_temperature_names else "unavailable",
        "metric": "npu_temperature_*",
        "metrics": npu_temperature_names,
        "source": SOURCE_THERMAL,
        "reason": (
            None
            if npu_temperature_names and npu_temperature_status == "available"
            else "No type 9 NPU temperature sensor was exposed by thermalservice."
        ),
    }
    return result


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _write_json_line(stream: TextIO, record: Mapping[str, Any]) -> None:
    stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n")
    stream.flush()


def _sample_record(
    session_id: str,
    sequence: int,
    started_ns: int,
    collected: Mapping[str, Any],
) -> dict[str, Any]:
    finished_ns = int(collected["finished_ns"])
    return {
        "type": "sample",
        "schema_version": SCHEMA_VERSION,
        "session_id": session_id,
        "sequence": sequence,
        "elapsed_s": (finished_ns - started_ns) / 1_000_000_000,
        "monotonic_ns": finished_ns,
        "collection_ms": collected["collection_ms"],
        "metrics": collected["metrics"],
        "errors": collected["errors"],
    }


def _summary_record(
    session_id: str,
    started_ns: int,
    finished_ns: int,
    status: str,
    sample_count: int,
    collection_overhead_ms: float,
    errors: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    return {
        "type": "summary",
        "schema_version": SCHEMA_VERSION,
        "session_id": session_id,
        "status": status,
        "elapsed_s": (finished_ns - started_ns) / 1_000_000_000,
        "sample_count": sample_count,
        "collection_overhead_ms": collection_overhead_ms,
        "errors": list(errors),
        "finished_at": _utc_timestamp(),
        "monotonic_ns": finished_ns,
    }


def record_session(
    client: AdbClient,
    duration_s: float,
    interval_s: float,
    output: str,
    package: Optional[str] = None,
    *,
    clock: Callable[[], int] = time.monotonic_ns,
    sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    if duration_s <= 0 or not math.isfinite(duration_s):
        raise UsageError("--duration must be a finite number greater than zero")
    if interval_s <= 0 or not math.isfinite(interval_s):
        raise UsageError("--interval must be a finite number greater than zero")
    if package is not None:
        package = validate_package(package)

    started_ns = clock()
    session_id = uuid.uuid4().hex
    stream: TextIO
    close_stream = False
    if output == "-":
        stream = sys.stdout
    else:
        try:
            stream = open(output, "x", encoding="utf-8")
        except OSError as exc:
            if isinstance(exc, FileExistsError):
                message = f"Output file already exists: {output!r}; choose a new path"
            else:
                message = f"Cannot create output {output!r}: {exc}"
            raise MonitorError(message, "output_error") from exc
        close_stream = True

    sample_count = 0
    overhead_ms = 0.0
    all_errors: list[Mapping[str, Any]] = []
    status = "completed"
    try:
        try:
            device = read_device_info(client)
        except AdbCommandError as exc:
            device = {"serial": client.serial}
            status = "disconnected" if exc.disconnected else "error"
            error = _error_record(exc)
            all_errors.append(error)
            session = {
                "type": "session",
                "schema_version": SCHEMA_VERSION,
                "session_id": session_id,
                "started_at": _utc_timestamp(),
                "started_monotonic_ns": started_ns,
                "serial": client.serial,
                "device": device,
                "capabilities": {},
                "config": {
                    "duration_s": duration_s,
                    "interval_s": interval_s,
                    "package": package,
                },
                "errors": [error],
            }
            _write_json_line(stream, session)
            finished_ns = clock()
            summary = _summary_record(
                session_id,
                started_ns,
                finished_ns,
                status,
                0,
                0.0,
                all_errors,
            )
            _write_json_line(stream, summary)
            return summary

        cpu_sampler = CpuSampler(client)
        first_collected = collect_sample(client, cpu_sampler, package, clock=clock)
        session = {
            "type": "session",
            "schema_version": SCHEMA_VERSION,
            "session_id": session_id,
            "started_at": _utc_timestamp(),
            "started_monotonic_ns": started_ns,
            "serial": client.serial,
            "device": device,
            "capabilities": build_capabilities(first_collected["metrics"]),
            "config": {
                "duration_s": duration_s,
                "interval_s": interval_s,
                "package": package,
            },
            "errors": [],
        }
        _write_json_line(stream, session)

        deadline_ns = started_ns + int(duration_s * 1_000_000_000)
        sequence = 0
        collected = first_collected
        while True:
            if sequence > 0:
                target_ns = started_ns + int(sequence * interval_s * 1_000_000_000)
                if target_ns > deadline_ns:
                    break
                remaining_s = (target_ns - clock()) / 1_000_000_000
                if remaining_s > 0:
                    sleeper(remaining_s)
                collected = collect_sample(client, cpu_sampler, package, clock=clock)
            sample = _sample_record(session_id, sequence, started_ns, collected)
            _write_json_line(stream, sample)
            sample_count += 1
            overhead_ms += float(collected["collection_ms"])
            all_errors.extend(collected["errors"])
            if any(error.get("code") == "device_disconnected" for error in collected["errors"]):
                status = "disconnected"
                break
            sequence += 1
            if clock() >= deadline_ns:
                break
        finished_ns = clock()
        summary = _summary_record(
            session_id,
            started_ns,
            finished_ns,
            status,
            sample_count,
            overhead_ms,
            all_errors,
        )
        _write_json_line(stream, summary)
        return summary
    except KeyboardInterrupt:
        status = "interrupted"
        finished_ns = clock()
        summary = _summary_record(
            session_id,
            started_ns,
            finished_ns,
            status,
            sample_count,
            overhead_ms,
            all_errors,
        )
        _write_json_line(stream, summary)
        return summary
    finally:
        if close_stream:
            stream.close()


class MonitorArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise UsageError(message)


def _positive_float(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a number") from exc
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = MonitorArgumentParser(description=__doc__)
    parser.add_argument("--adb", dest="adb_path", help="ADB executable path")
    parser.add_argument("--timeout", type=_positive_float, default=DEFAULT_TIMEOUT_S)
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_connection_args(command_parser: argparse.ArgumentParser) -> None:
        # Suppressing subparser defaults preserves values supplied before the
        # subcommand while still accepting the conventional after-command form.
        command_parser.add_argument(
            "--adb", dest="adb_path", default=argparse.SUPPRESS, help="ADB executable path"
        )
        command_parser.add_argument(
            "--timeout", type=_positive_float, default=argparse.SUPPRESS
        )

    devices = subparsers.add_parser("devices", help="list connected Android devices as JSON")
    add_connection_args(devices)
    devices.add_argument("--pretty", action="store_true", help="indent JSON output")

    probe = subparsers.add_parser("probe", help="collect one capability and metric snapshot as JSON")
    add_connection_args(probe)
    probe.add_argument("--serial", help="device serial when more than one phone is connected")
    probe.add_argument("--package", help="package for optional app memory collection")
    probe.add_argument("--pretty", action="store_true", help="indent JSON output")

    record = subparsers.add_parser("record", help="record an NDJSON monitoring session")
    add_connection_args(record)
    record.add_argument("--serial", help="device serial when more than one phone is connected")
    record.add_argument("--duration", type=_positive_float, default=DEFAULT_DURATION_S)
    record.add_argument("--interval", type=_positive_float, default=DEFAULT_INTERVAL_S)
    record.add_argument("--package", help="package for optional app memory collection")
    record.add_argument("--output", default="-", help="NDJSON output path, or - for stdout")
    return parser


def _print_json(value: Mapping[str, Any], pretty: bool = False) -> None:
    print(json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2 if pretty else None))


def _run_devices(args: argparse.Namespace) -> int:
    adb_path = discover_adb(args.adb_path)
    client = AdbClient(adb_path, timeout_s=args.timeout)
    devices = client.devices()
    _print_json(
        {
            "type": "devices",
            "schema_version": SCHEMA_VERSION,
            "adb": adb_path,
            "devices": [device.as_dict() for device in devices],
            "count": len(devices),
        },
        args.pretty,
    )
    return 0


def _selected_client(args: argparse.Namespace) -> tuple[str, Device, AdbClient]:
    adb_path = discover_adb(args.adb_path)
    listing_client = AdbClient(adb_path, timeout_s=args.timeout)
    selected = select_device(listing_client.devices(), args.serial)
    return adb_path, selected, AdbClient(adb_path, selected.serial, args.timeout)


def _run_probe(args: argparse.Namespace) -> int:
    adb_path, selected, client = _selected_client(args)
    if args.package is not None:
        args.package = validate_package(args.package)
    captured_ns = time.monotonic_ns()
    device = read_device_info(client)
    sampler = CpuSampler(client)
    collected = collect_sample(client, sampler, args.package)
    if collected["metrics"]["cpu_utilization"]["status"] == "warming_up":
        time.sleep(PROBE_CPU_WINDOW_S)
        before_ns = time.monotonic_ns()
        collected["metrics"]["cpu_utilization"] = sampler.sample()
        collected["collection_ms"] += (time.monotonic_ns() - before_ns) / 1_000_000
        if sampler.last_error is not None:
            collected["errors"].append(_error_record(sampler.last_error))
    finished_ns = time.monotonic_ns()
    _print_json(
        {
            "type": "probe",
            "schema_version": SCHEMA_VERSION,
            "captured_at": _utc_timestamp(),
            "monotonic_ns": finished_ns,
            "started_monotonic_ns": captured_ns,
            "adb": adb_path,
            "serial": selected.serial,
            "device": device,
            "capabilities": build_capabilities(collected["metrics"]),
            "metrics": collected["metrics"],
            "collection_overhead_ms": collected["collection_ms"],
            "errors": collected["errors"],
        },
        args.pretty,
    )
    return 1 if any(error.get("code") == "device_disconnected" for error in collected["errors"]) else 0


def _run_record(args: argparse.Namespace) -> int:
    _adb_path, _selected, client = _selected_client(args)
    if args.package is not None:
        args.package = validate_package(args.package)
    summary = record_session(
        client,
        args.duration,
        args.interval,
        args.output,
        args.package,
    )
    return 0 if summary["status"] == "completed" else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
        if args.command == "devices":
            return _run_devices(args)
        if args.command == "probe":
            return _run_probe(args)
        if args.command == "record":
            return _run_record(args)
        raise UsageError("a command is required")
    except MonitorError as exc:
        print(
            json.dumps(
                {
                    "type": "error",
                    "schema_version": SCHEMA_VERSION,
                    "error": {"code": exc.code, "message": exc.message},
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2
    except ValueError as exc:
        print(
            json.dumps(
                {
                    "type": "error",
                    "schema_version": SCHEMA_VERSION,
                    "error": {"code": "invalid_value", "message": str(exc)},
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2
    except OSError as exc:
        print(
            json.dumps(
                {
                    "type": "error",
                    "schema_version": SCHEMA_VERSION,
                    "error": {"code": "io_error", "message": str(exc)},
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    sys.exit(main())
