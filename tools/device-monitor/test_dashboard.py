import io
import json
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import urlopen

import dashboard


class SessionTests(unittest.TestCase):
    def session(self, content):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "session.ndjson"
        path.write_text(content)
        return path

    def test_partial_write_is_ignored_until_complete(self):
        path = self.session('{"type":"session","device":{"model":"Phone"}}\n'
                            '{"type":"sample","metrics":')
        report = dashboard.load_session(path)
        self.assertEqual(report["session"]["device"]["model"], "Phone")
        self.assertEqual(report["samples"], [])
        self.assertTrue(report["partial_line"])

    def test_corrupt_complete_line_is_reported(self):
        path = self.session('{"type":"session"}\nnot json\n')
        with self.assertRaisesRegex(ValueError, "line 2"):
            dashboard.load_session(path)

    def test_complete_final_record_without_newline_is_read(self):
        path = self.session('{"type":"session","device":{"model":"Phone"}}')
        report = dashboard.load_session(path)
        self.assertEqual(report["session"]["device"]["model"], "Phone")
        self.assertFalse(report["partial_line"])

    def test_rejects_multiple_sessions_and_inconsistent_units(self):
        for rows in [
            [{"type": "session"}, {"type": "session"}],
            [{"type": "sample", "metrics": {"x": {"status": "available", "value": 100, "unit": "ms"}}},
             {"type": "sample", "metrics": {"x": {"status": "available", "value": 2, "unit": "s"}}}],
        ]:
            with self.subTest(rows=rows):
                path = self.session(''.join(json.dumps(r) + '\n' for r in rows))
                with self.assertRaises(ValueError):
                    dashboard.load_session(path)

    def test_rejects_invalid_metric_types_and_nonfinite_json(self):
        for metric in [
            {"status": [], "unit": "%", "value": 1},
            {"status": "available", "unit": [], "value": 1},
            {"status": "available", "unit": "%", "value": float('nan')},
            {"status": "available", "unit": "%", "value": 10 ** 400},
        ]:
            with self.subTest(metric=metric):
                path = self.session(json.dumps({"type": "sample", "metrics": {"cpu": metric}}) + '\n')
                with self.assertRaises(ValueError):
                    dashboard.load_session(path)

    def test_summary_retains_run_configuration_for_comparison(self):
        report = {"session": {"device": {}, "interval_s": 2, "package": "a"},
                  "samples": [], "completion": None}
        before = dashboard.summarize(report)
        report["session"] = {"device": {}, "interval_s": 5, "package": "b"}
        after = dashboard.summarize(report)
        self.assertEqual(before["session"]["interval_s"], 2)
        result = dashboard.compare(before, after)
        self.assertTrue(any("configuration" in warning for warning in result["warnings"]))

    def test_rejects_unknown_records_and_records_after_completion(self):
        for rows in [[{"type": "sampel"}],
                     [{"type": "summary"}, {"type": "sample", "metrics": {}}]]:
            path = self.session(''.join(json.dumps(r) + '\n' for r in rows))
            with self.assertRaises(ValueError):
                dashboard.load_session(path)

    def test_comparison_reports_metrics_missing_from_a_run(self):
        before = {"device": {}, "metrics": {"cpu": {"mean": 10, "unit": "%"}}}
        after = {"device": {}, "metrics": {}}
        self.assertTrue(any("cpu" in text for text in dashboard.compare(before, after)["warnings"]))

    def test_summary_handles_large_finite_values_without_overflow(self):
        report = {"session": {}, "completion": None, "samples": [
            {"metrics": {"x": {"value": 1e308, "status": "available", "unit": "test"}}}
            for _ in range(2)]}
        self.assertEqual(dashboard.summarize(report)["metrics"]["x"]["mean"], 1e308)

    def test_summary_excludes_unavailable_and_nonfinite_values(self):
        path = self.session('\n'.join(json.dumps(row) for row in [
            {"type": "session", "device": {"model": "Phone"}},
            {"type": "sample", "elapsed_s": 0, "metrics": {
                "cpu": {"value": None, "status": "warming_up", "unit": "%"},
                "npu": {"value": None, "status": "unsupported", "unit": "%"}}},
            {"type": "sample", "elapsed_s": 1, "metrics": {
                "cpu": {"value": 20, "status": "available", "unit": "%"}}},
            {"type": "sample", "elapsed_s": 2, "metrics": {
                "cpu": {"value": 40, "status": "available", "unit": "%"}}},
        ]) + '\n')
        result = dashboard.summarize(dashboard.load_session(path))
        self.assertEqual(result["metrics"]["cpu"], {
            "unit": "%", "count": 2, "min": 20, "max": 40, "mean": 30})
        self.assertNotIn("npu", result["metrics"])
        self.assertEqual(result["coverage"]["npu"]["statuses"], {"unsupported": 1})
        self.assertEqual(result["sample_count"], 3)

    def test_rejects_samples_without_metric_objects(self):
        path = self.session('{"type":"sample","metrics":[]}\n')
        with self.assertRaisesRegex(ValueError, "metrics"):
            dashboard.load_session(path)

    def test_comparison_checks_units_and_zero_baseline(self):
        before = {"device": {"model": "A"}, "sample_count": 2, "metrics": {
            "cpu": {"unit": "%", "mean": 20},
            "power": {"unit": "mW", "mean": 0},
            "memory": {"unit": "KiB", "mean": 100}}}
        after = {"device": {"model": "B"}, "sample_count": 2, "metrics": {
            "cpu": {"unit": "%", "mean": 10},
            "power": {"unit": "mW", "mean": 10},
            "memory": {"unit": "MiB", "mean": 1}}}
        result = dashboard.compare(before, after)
        self.assertEqual(result["metrics"]["cpu"]["mean_change_percent"], -50)
        self.assertIsNone(result["metrics"]["power"]["mean_change_percent"])
        self.assertNotIn("memory", result["metrics"])
        self.assertTrue(any("device" in warning.lower() for warning in result["warnings"]))

    def test_server_refreshes_session_and_does_not_serve_arbitrary_files(self):
        path = self.session('{"type":"session","device":{"model":"Phone"}}\n')
        server = ThreadingHTTPServer(("127.0.0.1", 0), dashboard.handler_for(path))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        url = f"http://127.0.0.1:{server.server_port}"
        with urlopen(url + "/api/session") as response:
            self.assertEqual(json.load(response)["summary"]["sample_count"], 0)
        with path.open("a") as stream:
            stream.write('{"type":"sample","metrics":{}}\n')
        with urlopen(url + "/api/session") as response:
            self.assertEqual(json.load(response)["summary"]["sample_count"], 1)
        with self.assertRaises(HTTPError) as error:
            urlopen(url + "/test_dashboard.py")
        self.assertEqual(error.exception.code, 404)

    def test_official_perfetto_assets_and_generated_trace_are_served(self):
        path = self.session(
            '{"type":"session","session_id":"test-session","device":{"model":"Phone"}}\n'
            '{"type":"sample","elapsed_s":1.25,"metrics":{'
            '"cpu_utilization":{"status":"available","value":42,"unit":"%"}}}\n'
        )
        cache = path.parent / "cache"
        root = cache / "v-test"
        root.mkdir(parents=True)
        (root / ".ready").write_text("v-test")
        (root / "index.html").write_text("<title>Perfetto UI</title>")
        (root / "frontend_bundle.js").write_text("window.perfetto = true;")
        (root / "trace_processor.wasm").write_bytes(b"wasm-data")
        server = None
        with patch.object(dashboard, "PERFETTO_CACHE", cache), \
                patch.object(dashboard, "PERFETTO_VERSION", "v-test"):
            server = ThreadingHTTPServer(("127.0.0.1", 0), dashboard.handler_for(path))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            self.addCleanup(server.server_close)
            self.addCleanup(server.shutdown)
            url = f"http://127.0.0.1:{server.server_port}"
            with urlopen(url + "/") as response:
                self.assertEqual(response.read(), b"<title>Perfetto UI</title>")
            with urlopen(url + "/frontend_bundle.js") as response:
                self.assertEqual(response.headers.get_content_type(), "text/javascript")
                self.assertEqual(response.read(), b"window.perfetto = true;")
            with urlopen(url + "/trace_processor.wasm") as response:
                self.assertEqual(response.headers.get_content_type(), "application/wasm")
                self.assertEqual(response.read(), b"wasm-data")
            with urlopen(url + "/trace") as response:
                trace = json.load(response)
                self.assertEqual(response.headers.get_content_type(), "application/json")
                self.assertEqual(trace["traceEvents"][-1]["ts"], 1_250_000)
            with self.assertRaises(HTTPError) as error:
                urlopen(url + "/private.txt")
            self.assertEqual(error.exception.code, 404)
            for route in ["/../session.ndjson", "/%2e%2e/session.ndjson"]:
                with self.assertRaises(HTTPError) as error:
                    urlopen(url + route)
                self.assertEqual(error.exception.code, 404)

    def test_cli_explains_how_to_install_perfetto_ui(self):
        path = self.session('{"type":"session","device":{}}\n')
        missing_cache = path.parent / "missing-cache"
        stderr = io.StringIO()
        with patch.object(dashboard, "PERFETTO_CACHE", missing_cache), \
                patch.object(dashboard, "PERFETTO_VERSION", "v-test"), \
                patch("sys.argv", ["dashboard.py", str(path)]), \
                redirect_stderr(stderr):
            self.assertEqual(dashboard.main(), 1)
        self.assertIn("perfetto_local.py install", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
