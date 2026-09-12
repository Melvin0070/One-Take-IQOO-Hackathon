import io
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

import qnn_smoke


class QnnSmokeTests(unittest.TestCase):
    def test_remote_paths_are_limited_to_generated_staging_root(self):
        root = qnn_smoke.new_remote_root()

        self.assertTrue(qnn_smoke.remote_path(root, "qnn-net-run").startswith(root + "/"))
        self.assertTrue(qnn_smoke.remote_path(root, "libc++_shared.so").startswith(root + "/"))
        with self.assertRaises(qnn_smoke.SmokeError):
            qnn_smoke.remote_path(root, "../outside")
        with self.assertRaises(qnn_smoke.SmokeError):
            qnn_smoke.remote_path(root, ".")
        with self.assertRaises(qnn_smoke.SmokeError):
            qnn_smoke.assert_owned_remote("/data/local/tmp/device-monitor-qnn-other")

    def test_converter_archive_rejects_path_traversal_and_non_raw_members(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "weights.bin"
            with tarfile.open(archive_path, "w") as archive:
                member = tarfile.TarInfo("../escape.raw")
                member.size = 4
                archive.addfile(member, io.BytesIO(b"data"))
            with self.assertRaisesRegex(qnn_smoke.SmokeError, "Unexpected converter binary member"):
                qnn_smoke.extract_binary_weights(archive_path, Path(directory) / "out")

    def test_converter_archive_extracts_flat_raw_members(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "weights.bin"
            with tarfile.open(archive_path, "w") as archive:
                for name, data in (("weight.raw", b"weight"), ("bias.raw", b"bias")):
                    member = tarfile.TarInfo(name)
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            extracted = qnn_smoke.extract_binary_weights(archive_path, Path(directory) / "out")
            self.assertEqual([path.name for path in extracted], ["bias.raw", "weight.raw"])
            self.assertEqual((Path(directory) / "out/bias.raw").read_bytes(), b"bias")

    def test_output_comparison_reports_float_tolerance(self):
        with tempfile.TemporaryDirectory() as directory:
            before = Path(directory) / "before"
            after = Path(directory) / "after"
            before.mkdir()
            after.mkdir()
            (before / "result.raw").write_bytes(struct.pack("<2f", 1.0, 2.0))
            (after / "result.raw").write_bytes(struct.pack("<2f", 1.0, 2.0005))
            comparison = qnn_smoke.compare_output_files(before, after)
            self.assertEqual(comparison["common_files"], 1)
            self.assertTrue(comparison["files"][0]["within_1e-3"])
            self.assertGreater(comparison["files"][0]["max_abs_error"], 0.0)
            self.assertEqual(comparison["status"], "passed")

    def test_output_comparison_keeps_binary_files_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            before = Path(directory) / "before"
            after = Path(directory) / "after"
            before.mkdir()
            after.mkdir()
            (before / "result.raw").write_bytes(b"abc")
            (after / "result.raw").write_bytes(b"abd")
            result = qnn_smoke.compare_output_files(before, after)["files"][0]
            self.assertEqual(result["representation"], "bytes")
            self.assertFalse(result["identical"])
            self.assertEqual(
                qnn_smoke.compare_output_files(before, after)["status"], "failed"
            )

    def test_output_comparison_rejects_nonfinite_float(self):
        with tempfile.TemporaryDirectory() as directory:
            before = Path(directory) / "before"
            after = Path(directory) / "after"
            before.mkdir()
            after.mkdir()
            (before / "result.raw").write_bytes(struct.pack("<f", float("nan")))
            (after / "result.raw").write_bytes(struct.pack("<f", 1.0))
            with self.assertRaisesRegex(qnn_smoke.SmokeError, "non-finite"):
                qnn_smoke.compare_output_files(before, after)

    def test_htp_verification_does_not_report_cpu_marker(self):
        result = qnn_smoke.verify_htp_execution(
            "backend=libQnnHtp.so\nHexagon HTP graph execution\n", "profile: QNN HTP"
        )
        self.assertEqual(result["requested_backend"], "libQnnHtp.so")
        self.assertTrue(result["backend_request_present"])
        self.assertTrue(result["runtime_htp_marker_present"])
        self.assertFalse(result["cpu_fallback_marker_present"])
        self.assertEqual(result["npu_utilization"], "not measured by this smoke test")

    def test_create_output_dir_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "existing"
            output.mkdir()
            with self.assertRaisesRegex(qnn_smoke.SmokeError, "Refusing to overwrite"):
                qnn_smoke.create_output_dir(output)


if __name__ == "__main__":
    unittest.main()
