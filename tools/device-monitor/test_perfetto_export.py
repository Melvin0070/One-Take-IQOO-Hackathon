import json
from pathlib import Path
import tempfile
import unittest

import dashboard
import perfetto_export


class PerfettoExportTests(unittest.TestCase):
    def session(self, rows):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "session.ndjson"
        path.write_text("".join(json.dumps(row) + "\n" for row in rows))
        return path

    def report(self, samples, *, session=None, completion=None):
        path = self.session([
            {"type": "session", "session_id": "session-1", "device": {"model": "Phone"},
             "config": {"interval_s": 1.0}},
            *samples,
            *([] if completion is None else [completion]),
        ] if session is None else session)
        return dashboard.load_session(path)

    @staticmethod
    def sample(elapsed_s, metrics):
        return {"type": "sample", "elapsed_s": elapsed_s, "metrics": metrics}

    @staticmethod
    def metric(value, unit="%", status="available", source="test", reason=None):
        return {"status": status, "value": value, "unit": unit,
                "source": source, "reason": reason}

    def test_counter_timestamps_are_elapsed_seconds_in_microseconds(self):
        report = self.report([
            self.sample(0.25, {"cpu_utilization": self.metric(10)}),
            self.sample(1.75, {"cpu_utilization": self.metric(20)}),
        ])

        trace = perfetto_export.build_trace(report)
        counters = [event for event in trace["traceEvents"] if event["ph"] == "C"]
        self.assertEqual([event["ts"] for event in counters], [250_000, 1_750_000])
        self.assertEqual(counters[0]["pid"], perfetto_export.PROCESS_ID)
        self.assertEqual(counters[0]["tid"], perfetto_export.GROUPS["cpu"][0])
        self.assertEqual(counters[0]["args"], {"cpu_utilization [%]": 10})

    def test_unavailable_metrics_have_diagnostics_without_counter_events(self):
        report = self.report([
            self.sample(2, {
                "gpu_utilization": self.metric(
                    None, status="unsupported", source="portable collector",
                    reason="No portable GPU counter."),
                "cpu_utilization": self.metric(
                    None, status="warming_up", reason="Needs a second reading."),
            }),
        ])

        events = perfetto_export.build_trace(report)["traceEvents"]
        self.assertFalse(any(event["ph"] == "C" for event in events))
        diagnostics = [event for event in events if event["ph"] == "i"]
        by_metric = {event["args"]["metric"]: event for event in diagnostics}
        self.assertEqual(by_metric["gpu_utilization"]["args"], {
            "metric": "gpu_utilization",
            "status": "unsupported",
            "unit": "%",
            "source": "portable collector",
            "reason": "No portable GPU counter.",
        })
        self.assertEqual(by_metric["cpu_utilization"]["args"]["status"], "warming_up")

    def test_nonnumeric_available_value_is_preserved_as_diagnostic(self):
        report = self.report([self.sample(0, {"vendor_state": self.metric("active", "state")})])
        events = perfetto_export.convert_session(report)["traceEvents"]
        self.assertFalse(any(event["ph"] == "C" for event in events))
        diagnostic = next(event for event in events if event["ph"] == "i")
        self.assertEqual(diagnostic["args"]["value"], "active")
        self.assertEqual(diagnostic["args"]["value_type"], "str")

    def test_missing_metric_in_middle_of_session_has_diagnostic_without_zero(self):
        report = self.report([
            self.sample(0, {"cpu_utilization": self.metric(10)}),
            self.sample(1, {}),
            self.sample(2, {"cpu_utilization": self.metric(30)}),
        ])

        events = perfetto_export.build_trace(report)["traceEvents"]
        counters = [event for event in events if event["ph"] == "C"]
        self.assertEqual([event["ts"] for event in counters], [0, 2_000_000])
        missing = [event for event in events
                   if event["ph"] == "i" and event["ts"] == 1_000_000]
        self.assertEqual(len(missing), 1)
        self.assertEqual(missing[0]["args"], {
            "metric": "cpu_utilization",
            "status": "unavailable",
            "unit": "%",
            "source": None,
            "reason": "Metric absent from sample",
        })

    def test_metric_names_and_group_tracks_are_stable(self):
        first = self.report([
            self.sample(0, {
                "memory_total": self.metric(100, "bytes"),
                "cpu_utilization": self.metric(10),
            }),
            self.sample(1, {"cpu_utilization": self.metric(20)}),
        ])
        second = self.report([
            self.sample(0, {"cpu_utilization": self.metric(10)}),
            self.sample(1, {
                "cpu_utilization": self.metric(20),
                "memory_total": self.metric(100, "bytes"),
            }),
        ])

        def shape(report):
            trace = perfetto_export.build_trace(report)
            return (
                sorted((event["name"], event.get("tid"), tuple(event["args"]))
                       for event in trace["traceEvents"] if event["ph"] == "C"),
                [(event["name"], event["tid"], event["args"])
                 for event in trace["traceEvents"]
                 if event["ph"] == "M" and event["name"] == "thread_name"],
            )

        self.assertEqual(shape(first), shape(second))
        self.assertEqual(
            [event["args"] for event in perfetto_export.build_trace(first)["traceEvents"]
             if event["ph"] == "C"],
            [{"cpu_utilization [%]": 10}, {"memory_total [bytes]": 100},
             {"cpu_utilization [%]": 20}],
        )

    def test_boolean_state_is_numeric_counter_with_state_unit(self):
        report = self.report([
            self.sample(0, {"battery_charging": self.metric(False, "state")}),
            self.sample(1, {"battery_charging": self.metric(True, "state")}),
        ])

        counters = [event for event in perfetto_export.build_trace(report)["traceEvents"]
                    if event["ph"] == "C"]
        self.assertEqual([event["args"] for event in counters],
                         [{"battery_charging [state]": 0}, {"battery_charging [state]": 1}])

    def test_malformed_input_and_elapsed_values_are_rejected(self):
        path = self.session([
            {"type": "session"},
            {"type": "sample", "elapsed_s": 0, "metrics": {"cpu": self.metric(1)}},
        ])
        path.write_text('{"type":"session"}\nnot json\n')
        with self.assertRaisesRegex(ValueError, "line 2"):
            perfetto_export.load_report(path)

        for elapsed in [-1, "1", True]:
            with self.subTest(elapsed=elapsed):
                report = self.report([self.sample(elapsed, {"cpu": self.metric(1)})])
                with self.assertRaises(ValueError):
                    perfetto_export.build_trace(report)

    def test_cli_requires_distinct_input_and_output_files(self):
        path = self.session([{"type": "session"}])
        with self.assertRaises(SystemExit) as raised:
            perfetto_export.main([str(path), str(path)])
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
