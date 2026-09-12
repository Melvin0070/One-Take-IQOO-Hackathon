#!/usr/bin/env python3
"""Tests for the standard-library Android device monitor."""

from __future__ import annotations

import json
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch
from types import SimpleNamespace


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import device_monitor  # noqa: E402


class ParsingTests(unittest.TestCase):
    def test_parse_proc_stat_delta_returns_busy_percentage(self) -> None:
        before = device_monitor.parse_proc_stat(
            "cpu  100 20 30 800 50 0 0 0 0 0\n"
            "cpu0 50 10 15 400 20 0 0 0 0 0\n"
        )["cpu"]
        after = device_monitor.parse_proc_stat(
            "cpu  120 20 40 850 60 0 0 0 0 0\n"
            "cpu0 60 10 20 425 25 0 0 0 0 0\n"
        )["cpu"]

        utilization = device_monitor.cpu_utilization(before, after)

        self.assertAlmostEqual(utilization, 33.3333333333)

    def test_parse_proc_stat_rejects_missing_aggregate(self) -> None:
        self.assertNotIn("cpu", device_monitor.parse_proc_stat("cpu0 1 2 3 4\n"))

    def test_parse_proc_stat_does_not_double_count_guest_time(self) -> None:
        parsed = device_monitor.parse_proc_stat("cpu 1 2 3 4 5 6 7 8 90 100\n")

        self.assertEqual(parsed["cpu"]["total"], sum(range(1, 9)))

    def test_parse_meminfo_converts_kernel_units_to_bytes(self) -> None:
        parsed = device_monitor.parse_meminfo(
            "MemTotal:       4096000 kB\n"
            "MemAvailable:   2048000 kB\n"
            "HugePages_Total:       2\n"
        )

        self.assertEqual(parsed["MemTotal"], 4096000 * 1024)
        self.assertEqual(parsed["MemAvailable"], 2048000 * 1024)
        self.assertEqual(parsed["HugePages_Total"], 2)

    def test_parse_battery_converts_temperature_and_current(self) -> None:
        parsed = device_monitor.parse_battery(
            "Current Battery Service state:\n"
            "  AC powered: false\n"
            "  USB powered: true\n"
            "  status: 2\n"
            "  level: 75\n"
            "  scale: 100\n"
            "  temperature: 283\n"
            "  voltage: 4210\n"
            "  current now: -320000\n"
        )

        self.assertEqual(parsed["level"], 75)
        self.assertAlmostEqual(parsed["temperature_c"], 28.3)
        self.assertEqual(parsed["voltage_mv"], 4210)
        self.assertEqual(parsed["current_raw"], -320000)
        self.assertIsNone(parsed["current_unit"])
        self.assertTrue(parsed["charging"])

        metrics = device_monitor.battery_metrics(parsed)
        self.assertEqual(metrics["battery_current"]["status"], "unavailable")
        self.assertEqual(metrics["battery_current_raw"]["value"], -320000)

    def test_battery_metrics_do_not_invent_charging_state(self) -> None:
        metrics = device_monitor.battery_metrics(device_monitor.parse_battery("  level: 75\n  scale: 100\n"))

        self.assertEqual(metrics["battery_charging"]["status"], "unavailable")
        self.assertIsNone(metrics["battery_charging"]["value"])

    def test_battery_full_and_plugged_is_powered_but_not_charging(self) -> None:
        values = device_monitor.parse_battery(
            "  USB powered: true\n  status: 5\n  level: 100\n  scale: 100\n"
        )
        metrics = device_monitor.battery_metrics(values)

        self.assertFalse(metrics["battery_charging"]["value"])
        self.assertTrue(metrics["battery_powered"]["value"])

    def test_parse_thermal_service_ignores_cached_thresholds(self) -> None:
        parsed = device_monitor.parse_thermalservice(
            "Thermal Service state:\n"
            "Current temperatures from HAL:\n"
            "Temperature{mValue=34.5, mType=0, mName=CPU, mStatus=0}\n"
            "Temperature{mValue=31.0, mType=5, mName=Battery, mStatus=0}\n"
            "Current thermal status: 2\n"
            "Temperature static thresholds:\n"
            "Temperature{mValue=99.0, mType=0, mName=CPU, mStatus=0}\n"
            "Current thermal status: 6\n"
        )

        self.assertEqual(parsed["status"], 2)
        self.assertEqual(parsed["sensors"]["CPU"]["value_c"], 34.5)
        self.assertNotIn("99.0", json.dumps(parsed))

    def test_thermal_metrics_filter_virtual_types_and_expose_npu_temperatures(self) -> None:
        parsed = device_monitor.parse_thermalservice(
            "Thermal Status: 0\n"
            "Cached temperatures:\n"
            "Temperature{mValue=90.0, mType=9, mName=nsp0, mStatus=0}\n"
            "Current temperatures from HAL:\n"
            "Temperature{mValue=34.5, mType=0, mName=CPU, mStatus=0}\n"
            "Temperature{mValue=33.0, mType=2, mName=BAT, mStatus=0}\n"
            "Temperature{mValue=4.06, mType=6, mName=vbat, mStatus=0}\n"
            "Temperature{mValue=1200.0, mType=7, mName=ibat, mStatus=0}\n"
            "Temperature{mValue=75.0, mType=8, mName=bcl_percent, mStatus=0}\n"
            "Temperature{mValue=28.7, mType=9, mName=nsp0, mStatus=0}\n"
            "Temperature{mValue=29.1, mType=9, mName=nsp7, mStatus=0}\n"
            "Temperature static thresholds from HAL:\n"
            "Temperature{mValue=99.0, mType=9, mName=nsp0, mStatus=0}\n"
        )

        metrics = device_monitor.thermal_metrics(parsed)

        self.assertEqual(metrics["thermal_sensor_cpu"]["value"], 34.5)
        self.assertEqual(metrics["thermal_sensor_bat"]["value"], 33.0)
        self.assertNotIn("thermal_sensor_vbat", metrics)
        self.assertNotIn("thermal_sensor_ibat", metrics)
        self.assertNotIn("thermal_sensor_bcl_percent", metrics)
        self.assertEqual(metrics["npu_temperature_nsp0"]["value"], 28.7)
        self.assertEqual(metrics["npu_temperature_nsp7"]["value"], 29.1)
        self.assertNotIn("thermal_sensor_nsp0", metrics)

    def test_npu_temperature_capability_is_separate_from_utilization(self) -> None:
        metrics = device_monitor.thermal_metrics(
            {"status": 0, "sensors": {"nsp0": {"value_c": 28.7, "type": 9, "status": 0}}}
        )
        metrics.update(device_monitor.unsupported_accelerator_metrics())

        capabilities = device_monitor.build_capabilities(metrics)

        self.assertEqual(capabilities["npu_temperature"]["status"], "available")
        self.assertEqual(capabilities["npu"]["status"], "unsupported")

    def test_parse_devices_preserves_states_and_attributes(self) -> None:
        devices = device_monitor.parse_devices(
            "List of devices attached\n"
            "ABC123\tdevice product:a52x model:SM_A528B transport_id:1\n"
            "XYZ999\tunauthorized usb:1-2\n"
        )

        self.assertEqual(devices[0].serial, "ABC123")
        self.assertEqual(devices[0].state, "device")
        self.assertEqual(devices[0].attributes["model"], "SM_A528B")
        self.assertEqual(devices[1].state, "unauthorized")

    def test_select_device_reports_multiple_and_unauthorized_explicitly(self) -> None:
        devices = device_monitor.parse_devices(
            "List of devices attached\n"
            "one\tdevice\n"
            "two\tdevice\n"
        )
        with self.assertRaisesRegex(device_monitor.DeviceSelectionError, "multiple"):
            device_monitor.select_device(devices)

        unauthorized = device_monitor.parse_devices(
            "List of devices attached\n"
            "one\tunauthorized\n"
        )
        with self.assertRaisesRegex(device_monitor.DeviceSelectionError, "unauthorized"):
            device_monitor.select_device(unauthorized)

    def test_package_validation_rejects_shell_syntax(self) -> None:
        self.assertEqual(device_monitor.validate_package("com.example.app"), "com.example.app")
        with self.assertRaisesRegex(ValueError, "package"):
            device_monitor.validate_package("com.example.app;id")


class AdbDiscoveryTests(unittest.TestCase):
    def test_probe_timestamp_is_collection_end_and_retains_start(self) -> None:
        clock = [100]

        class Client:
            serial = "test"

            def shell(self, *args):
                clock[0] += 100
                return {
                    ("getprop",): "[ro.product.model]: [TEST]",
                    ("cat", "/proc/stat"): "cpu 1 2 3 4",
                    ("cat", "/proc/meminfo"): "MemTotal: 10 kB",
                    ("dumpsys", "battery"): "level: 50\nscale: 100",
                    ("dumpsys", "thermalservice"): "Thermal Status: 0",
                }[args]

        selected = device_monitor.Device("test", "device", {})
        output = io.StringIO()
        with patch.object(device_monitor, "_selected_client", return_value=("adb", selected, Client())), \
                patch.object(device_monitor.time, "monotonic_ns", side_effect=lambda: clock[0]), \
                patch.object(device_monitor.time, "sleep"), \
                patch.object(sys, "stdout", output):
            device_monitor._run_probe(SimpleNamespace(package=None, pretty=False))
        result = json.loads(output.getvalue())
        self.assertEqual(result["monotonic_ns"], clock[0])
        self.assertEqual(result["started_monotonic_ns"], 100)

    def test_explicit_adb_path_is_selected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            adb = Path(temp_dir) / "adb"
            adb.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR)

            self.assertEqual(
                device_monitor.discover_adb(str(adb)),
                str(adb.resolve()),
            )

    def test_sdk_environment_path_is_selected_before_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            sdk = Path(temp_dir) / "sdk"
            adb = sdk / "platform-tools" / "adb"
            adb.parent.mkdir(parents=True)
            adb.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR)

            found = device_monitor.discover_adb(
                environment={"ANDROID_HOME": str(sdk)},
                path_lookup=lambda _name: None,
                home=Path(temp_dir),
            )

            self.assertEqual(found, str(adb.resolve()))

    def test_invalid_explicit_adb_does_not_fall_back_to_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            sdk = Path(temp_dir) / "sdk"
            adb = sdk / "platform-tools" / "adb"
            adb.parent.mkdir(parents=True)
            adb.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR)

            with self.assertRaisesRegex(device_monitor.AdbNotFoundError, "explicit-adb"):
                device_monitor.discover_adb(
                    "explicit-adb",
                    environment={"ANDROID_HOME": str(sdk)},
                    path_lookup=lambda _name: None,
                    home=Path(temp_dir),
                )

    def test_adb_serial_not_found_is_classified_as_disconnect(self) -> None:
        error = device_monitor.AdbCommandError(
            ("adb", "-s", "serial"),
            "ADB command failed: error: device 'serial' not found",
        )

        self.assertEqual(error.code, "device_disconnected")


class FakeAdbIntegrationTests(unittest.TestCase):
    FAKE_ADB = textwrap.dedent(
        r"""
        #!/usr/bin/env python3
        import os
        import sys

        args = sys.argv[1:]
        if args and args[0] == "devices":
            if os.environ.get("FAKE_ADB_MULTI") == "1":
                print("List of devices attached")
                print("one\tdevice product:one model:ONE")
                print("two\tdevice product:two model:TWO")
            else:
                print("List of devices attached")
                print("test-serial\tdevice product:test model:TEST")
            raise SystemExit(0)

        if args and args[0] == "-s":
            args = args[2:]
        if args[:1] != ["shell"]:
            print("unexpected adb invocation", file=sys.stderr)
            raise SystemExit(2)
        command = args[1:]
        if command == ["getprop"]:
            print("[ro.product.manufacturer]: [TestCo]")
            print("[ro.product.model]: [TEST]")
            print("[ro.build.version.release]: [14]")
            print("[ro.build.version.sdk]: [34]")
        elif command == ["cat", "/proc/stat"]:
            print("cpu  100 20 30 800 50 0 0 0 0 0")
        elif command == ["cat", "/proc/meminfo"]:
            print("MemTotal:       4096000 kB")
            print("MemAvailable:   2048000 kB")
        elif command == ["dumpsys", "battery"]:
            print("  USB powered: true")
            print("  level: 75")
            print("  scale: 100")
            print("  temperature: 283")
            print("  voltage: 4210")
        elif command == ["dumpsys", "thermalservice"]:
            print("Current thermal status: 0")
            print("Temperature{mValue=34.5, mType=0, mName=CPU, mStatus=0}")
            print("Temperature static thresholds:")
            print("Temperature{mValue=99.0, mType=0, mName=CPU, mStatus=0}")
        elif len(command) == 3 and command[:2] == ["dumpsys", "meminfo"]:
            print("             TOTAL        12345        23456")
        else:
            print("unknown shell command", command, file=sys.stderr)
            raise SystemExit(1)
        """
    )

    def make_fake_adb(self, directory: Path) -> Path:
        adb = directory / "adb"
        adb.write_text(self.FAKE_ADB.lstrip(), encoding="utf-8")
        adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
        return adb

    def test_record_writes_session_samples_and_summary_ndjson(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            directory = Path(temp_dir)
            adb = self.make_fake_adb(directory)
            output = directory / "session.ndjson"
            command = [
                    sys.executable,
                    str(TOOL_DIR / "device_monitor.py"),
                    "record",
                    "--adb",
                    str(adb),
                    "--serial",
                    "test-serial",
                    "--duration",
                    "0.03",
                    "--interval",
                    "0.01",
                    "--package",
                    "com.example.app",
                    "--output",
                    str(output),
                ]
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            records = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(records[0]["type"], "session")
            self.assertEqual(records[-1]["type"], "summary")
            samples = [record for record in records if record["type"] == "sample"]
            self.assertGreaterEqual(len(samples), 1)
            self.assertEqual(records[-1]["status"], "completed")
            for metric in samples[0]["metrics"].values():
                self.assertEqual(set(metric), {"status", "value", "unit", "source", "reason"})
            self.assertEqual(samples[0]["metrics"]["gpu_utilization"]["status"], "unsupported")
            self.assertIsNone(samples[0]["metrics"]["gpu_utilization"]["value"])
            self.assertEqual(samples[0]["metrics"]["thermal_sensor_cpu"]["value"], 34.5)

            second = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(second.returncode, 2)
            self.assertEqual(json.loads(second.stderr)["error"]["code"], "output_error")

    def test_probe_without_serial_reports_multiple_devices_as_json_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            directory = Path(temp_dir)
            adb = self.make_fake_adb(directory)
            result = subprocess.run(
                [
                    sys.executable,
                    str(TOOL_DIR / "device_monitor.py"),
                    "probe",
                    "--adb",
                    str(adb),
                ],
                env={**os.environ, "FAKE_ADB_MULTI": "1"},
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(result.returncode, 2)
            error = json.loads(result.stderr)
            self.assertEqual(error["type"], "error")
            self.assertEqual(error["error"]["code"], "multiple_devices")
            self.assertIn("--serial", error["error"]["message"])


if __name__ == "__main__":
    unittest.main()
