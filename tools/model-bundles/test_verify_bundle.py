import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import verify_bundle


SCRIPT = Path(__file__).with_name("verify_bundle.py")


class BundleVerifierTests(unittest.TestCase):
    def make_bundle(self):
        root = Path(tempfile.mkdtemp())
        bundle = root / "bundle"
        bundle.mkdir()
        payloads = {
            "encoder": b"synthetic encoder",
            "decoder": b"synthetic decoder",
            "vocabulary": b"synthetic vocabulary",
        }
        artifacts = []
        for role, data in payloads.items():
            path = bundle / f"{role}.bin"
            path.write_bytes(data)
            artifacts.append(
                {
                    "role": role,
                    "path": path.name,
                    "size_bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
        manifest = {
            "schema_version": 1,
            "model_id": "qualcomm/whisper-tiny",
            "model_version": "synthetic-1",
            "target": {
                "device_model": "I2501",
                "soc_model": "SM8850",
                "soc_variant": "V81",
            },
            "qairt_version": "synthetic-qairt",
            "voice_ai_version": "synthetic-voice-ai",
            "source": "https://example.invalid/synthetic-model",
            "license": "https://example.invalid/synthetic-license",
            "artifacts": artifacts,
        }
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return root, bundle, manifest_path, manifest

    def run_cli(self, manifest_path, bundle_path):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(manifest_path), str(bundle_path)],
            capture_output=True,
            text=True,
        )

    def test_valid_bundle_reports_integrity_without_execution_claim(self):
        root, bundle, manifest_path, _ = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))

        result = verify_bundle.verify_bundle(manifest_path, bundle)

        self.assertEqual(
            result,
            {
                "artifact_count": 3,
                "artifact_integrity_verified": True,
                "model_execution_verified": False,
                "model_id": "qualcomm/whisper-tiny",
                "schema_version": 1,
            },
        )

    def test_cli_prints_success_json(self):
        root, bundle, manifest_path, _ = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))

        completed = self.run_cli(manifest_path, bundle)

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), {
            "artifact_count": 3,
            "artifact_integrity_verified": True,
            "model_execution_verified": False,
            "model_id": "qualcomm/whisper-tiny",
            "schema_version": 1,
        })
        self.assertEqual(completed.stderr, "")

    def test_cli_reports_malformed_json_without_traceback(self):
        root, bundle, _, _ = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        manifest_path = root / "malformed.json"
        manifest_path.write_text("{", encoding="utf-8")

        completed = self.run_cli(manifest_path, bundle)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("error:", completed.stderr)
        self.assertNotIn("Traceback", completed.stderr)

    def test_cli_reports_unsupported_metadata_without_traceback(self):
        root, bundle, manifest_path, manifest = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        manifest["unsupported"] = "metadata"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        completed = self.run_cli(manifest_path, bundle)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("error:", completed.stderr)
        self.assertIn("unsupported metadata", completed.stderr)
        self.assertNotIn("Traceback", completed.stderr)

    def test_cli_rejects_duplicate_json_keys_without_traceback(self):
        root, bundle, manifest_path, manifest = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        serialized = json.dumps(manifest).replace(
            '"model_id": "qualcomm/whisper-tiny"',
            '"model_id": "first", "model_id": "second"',
            1,
        )
        manifest_path.write_text(serialized, encoding="utf-8")

        completed = self.run_cli(manifest_path, bundle)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("duplicate", completed.stderr)
        self.assertNotIn("Traceback", completed.stderr)

    def test_cli_reports_corrupt_artifact_without_traceback(self):
        root, bundle, manifest_path, _ = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        data = bytearray((bundle / "encoder.bin").read_bytes())
        data[0] ^= 1
        (bundle / "encoder.bin").write_bytes(data)

        completed = self.run_cli(manifest_path, bundle)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("sha256 mismatch", completed.stderr)
        self.assertNotIn("Traceback", completed.stderr)

    def test_cli_reports_deeply_nested_json_without_traceback(self):
        root, bundle, _, _ = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        manifest_path = root / "deep.json"
        manifest_path.write_text("[" * 10000 + "]" * 10000, encoding="utf-8")

        completed = self.run_cli(manifest_path, bundle)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("error:", completed.stderr)
        self.assertIn("malformed manifest JSON", completed.stderr)
        self.assertNotIn("Traceback", completed.stderr)

    def test_rejects_artifacts_that_alias_the_same_regular_file(self):
        module = verify_bundle
        for link_kind, role in (("hardlink", "decoder"), ("symlink", "vocabulary")):
            with self.subTest(link_kind=link_kind):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                source = bundle / "encoder.bin"
                alias = bundle / f"{role}.bin"
                alias.unlink()
                if link_kind == "hardlink":
                    alias.hardlink_to(source)
                else:
                    alias.symlink_to(source)
                source_data = source.read_bytes()
                for artifact in manifest["artifacts"]:
                    if artifact["role"] == role:
                        artifact["size_bytes"] = len(source_data)
                        artifact["sha256"] = hashlib.sha256(source_data).hexdigest()
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

                with self.assertRaisesRegex(module.BundleError, "identity|same file"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_unsupported_schema_and_metadata(self):
        module = verify_bundle
        for field, value in (("schema_version", 2), ("unsupported", True)):
            with self.subTest(field=field):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest[field] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "schema|unsupported"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_non_string_artifact_roles(self):
        module = verify_bundle
        for role in ([], {}, None, 1):
            with self.subTest(role=role):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest["artifacts"][0]["role"] = role
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "role"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_wrong_target(self):
        module = verify_bundle
        for field, value in (("device_model", "other"), ("soc_model", "SM8450"),
                             ("soc_variant", "V79")):
            with self.subTest(field=field):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest["target"][field] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "target"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_missing_and_empty_metadata(self):
        module = verify_bundle
        for field in ("model_id", "model_version", "qairt_version", "voice_ai_version",
                      "source", "license"):
            for missing in (False, True):
                with self.subTest(field=field, missing=missing):
                    root, bundle, manifest_path, manifest = self.make_bundle()
                    self.addCleanup(lambda root=root: self._remove_tree(root))
                    if missing:
                        del manifest[field]
                    else:
                        manifest[field] = ""
                    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                    with self.assertRaisesRegex(module.BundleError, field):
                        module.verify_bundle(manifest_path, bundle)

    def test_requires_exactly_the_three_artifact_roles(self):
        module = verify_bundle
        for artifacts in (
            lambda manifest: manifest["artifacts"].__setitem__(0, dict(manifest["artifacts"][1])),
            lambda manifest: manifest["artifacts"].pop(),
            lambda manifest: manifest["artifacts"].append(dict(manifest["artifacts"][0])),
        ):
            root, bundle, manifest_path, manifest = self.make_bundle()
            self.addCleanup(lambda root=root: self._remove_tree(root))
            artifacts(manifest)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(module.BundleError, "role|artifact"):
                module.verify_bundle(manifest_path, bundle)

    def test_rejects_duplicate_artifact_paths(self):
        module = verify_bundle
        root, bundle, manifest_path, manifest = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        manifest["artifacts"][1]["path"] = manifest["artifacts"][0]["path"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(module.BundleError, "path"):
            module.verify_bundle(manifest_path, bundle)

    def test_rejects_unsafe_artifact_paths(self):
        module = verify_bundle
        for unsafe_path in ("../encoder.bin", "/tmp/encoder.bin", "C:\\encoder.bin",
                            "nested\\encoder.bin", "./encoder.bin"):
            with self.subTest(path=unsafe_path):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest["artifacts"][0]["path"] = unsafe_path
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "path"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_symlink_that_escapes_bundle(self):
        module = verify_bundle
        root, bundle, manifest_path, manifest = self.make_bundle()
        self.addCleanup(lambda: self._remove_tree(root))
        outside = root / "outside.bin"
        outside.write_bytes(b"outside")
        (bundle / "encoder.bin").unlink()
        (bundle / "encoder.bin").symlink_to(outside)
        manifest["artifacts"][0]["size_bytes"] = outside.stat().st_size
        manifest["artifacts"][0]["sha256"] = hashlib.sha256(outside.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(module.BundleError, "outside|bundle"):
            module.verify_bundle(manifest_path, bundle)

    def test_rejects_missing_and_non_regular_files(self):
        module = verify_bundle
        for replacement in ("missing", "directory"):
            with self.subTest(replacement=replacement):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                artifact = manifest["artifacts"][0]
                (bundle / artifact["path"]).unlink()
                if replacement == "directory":
                    (bundle / artifact["path"]).mkdir()
                artifact["size_bytes"] = 1
                artifact["sha256"] = "0" * 64
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "file|directory|missing"):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_size_and_hash_mismatches(self):
        module = verify_bundle
        for field in ("size_bytes", "sha256"):
            with self.subTest(field=field):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                if field == "size_bytes":
                    manifest["artifacts"][0][field] += 1
                else:
                    manifest["artifacts"][0][field] = "0" * 64
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, field.replace("_", " ")):
                    module.verify_bundle(manifest_path, bundle)

    def test_rejects_invalid_size_types_and_hash_format(self):
        module = verify_bundle
        for size in (True, 0, -1, "3"):
            with self.subTest(size=size):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest["artifacts"][0]["size_bytes"] = size
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "size_bytes"):
                    module.verify_bundle(manifest_path, bundle)
        for digest in ("A" * 64, "f" * 63, "z" * 64):
            with self.subTest(digest=digest):
                root, bundle, manifest_path, manifest = self.make_bundle()
                self.addCleanup(lambda root=root: self._remove_tree(root))
                manifest["artifacts"][0]["sha256"] = digest
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(module.BundleError, "sha256"):
                    module.verify_bundle(manifest_path, bundle)

    @staticmethod
    def _remove_tree(root):
        for path in sorted(root.rglob("*"), key=lambda item: len(item.parts), reverse=True):
            if path.is_dir() and not path.is_symlink():
                path.rmdir()
            else:
                path.unlink()
        root.rmdir()


if __name__ == "__main__":
    unittest.main()
