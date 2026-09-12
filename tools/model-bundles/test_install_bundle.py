import hashlib
from pathlib import Path
import shutil
import tempfile
import unittest
import warnings
from zipfile import ZIP_DEFLATED, ZipFile

import install_bundle


class BundleInstallerTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="onetake-bundle-install-"))

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_installs_and_reuses_verified_bundle(self):
        payloads = self.payloads()
        archive = self.archive(payloads)
        spec = self.spec(archive, payloads)
        destination = self.root / "store"

        first = install_bundle.install_archive(archive, destination, spec)
        second = install_bundle.install_archive(archive, destination, spec)

        self.assertEqual(first, second)
        self.assertEqual(sorted(path.name for path in first.iterdir()), sorted(payloads))
        self.assertEqual(1, len(list(destination.glob("v-*"))))
        self.assertEqual(first, install_bundle.resolve_installed(destination, spec))

    def test_corrupt_archive_preserves_previous_active_bundle(self):
        payloads = self.payloads()
        archive = self.archive(payloads)
        spec = self.spec(archive, payloads)
        destination = self.root / "store"
        previous = install_bundle.install_archive(archive, destination, spec)
        corrupt = self.root / "corrupt.zip"
        corrupt.write_bytes(archive.read_bytes())
        data = bytearray(corrupt.read_bytes())
        data[-1] ^= 1
        corrupt.write_bytes(data)

        with self.assertRaisesRegex(install_bundle.InstallError, "SHA-256"):
            install_bundle.install_archive(corrupt, destination, spec)

        self.assertEqual(previous, install_bundle.resolve_installed(destination, spec))
        self.assertEqual([], list(destination.glob(".staging-*")))

    def test_cancellation_preserves_previous_active_bundle(self):
        payloads = self.payloads()
        archive = self.archive(payloads)
        spec = self.spec(archive, payloads)
        destination = self.root / "store"
        previous = install_bundle.install_archive(archive, destination, spec)

        with self.assertRaisesRegex(install_bundle.InstallError, "cancelled"):
            install_bundle.install_archive(archive, destination, spec, cancel=lambda: True)

        self.assertEqual(previous, install_bundle.resolve_installed(destination, spec))

    def test_rejects_traversal_and_duplicate_entries(self):
        payloads = self.payloads()
        traversal = self.archive(payloads, extra=[("../outside.bin", b"outside")])
        traversal_spec = self.spec(traversal, payloads)
        with self.assertRaisesRegex(install_bundle.InstallError, "unsafe entry"):
            install_bundle.install_archive(traversal, self.root / "traversal", traversal_spec)
        self.assertFalse((self.root / "outside.bin").exists())

        duplicate = self.archive(
            payloads,
            extra=[(install_bundle.ARCHIVE_ROOT + "encoder.bin", payloads["encoder.bin"])],
        )
        duplicate_spec = self.spec(duplicate, payloads)
        with self.assertRaisesRegex(install_bundle.InstallError, "duplicate"):
            install_bundle.install_archive(duplicate, self.root / "duplicate", duplicate_spec)

    def test_rejects_oversized_and_incomplete_archives(self):
        payloads = self.payloads()
        oversized_payloads = dict(payloads)
        oversized_payloads["encoder.bin"] = b"too-large"
        oversized = self.archive(oversized_payloads)
        with self.assertRaisesRegex(install_bundle.InstallError, "size mismatch"):
            install_bundle.install_archive(
                oversized,
                self.root / "oversized",
                self.spec(oversized, payloads),
            )

        incomplete_payloads = dict(payloads)
        del incomplete_payloads["vocab.bin"]
        incomplete = self.archive(incomplete_payloads)
        with self.assertRaisesRegex(install_bundle.InstallError, "incomplete"):
            install_bundle.install_archive(
                incomplete,
                self.root / "incomplete",
                self.spec(incomplete, payloads),
            )

    def test_rejects_invalid_pointer(self):
        payloads = self.payloads()
        archive = self.archive(payloads)
        spec = self.spec(archive, payloads)
        destination = self.root / "store"
        installed = install_bundle.install_archive(archive, destination, spec)
        (destination / "active").write_text("../outside\n", encoding="utf-8")

        self.assertIsNone(install_bundle.resolve_installed(destination, spec))
        self.assertTrue(installed.is_dir())

    def payloads(self):
        return {
            "encoder.bin": b"encoder",
            "decoder.bin": b"decoder",
            "vocab.bin": b"vocab",
            "metadata.json": b"metadata",
            "config.json": b"config",
        }

    def archive(self, payloads, extra=()):
        archive = self.root / f"archive-{len(list(self.root.glob('archive-*')))}.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(archive, "w", ZIP_DEFLATED) as output:
                output.writestr(install_bundle.ARCHIVE_ROOT, b"")
                for name, data in payloads.items():
                    output.writestr(install_bundle.ARCHIVE_ROOT + name, data)
                for name, data in extra:
                    output.writestr(name, data)
        return archive

    def spec(self, archive, payloads):
        artifacts = tuple(
            install_bundle.Artifact(name, len(data), hashlib.sha256(data).hexdigest())
            for name, data in payloads.items()
        )
        return install_bundle.BundleSpec(
            archive_size_bytes=archive.stat().st_size,
            archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
            artifacts=artifacts,
        )


if __name__ == "__main__":
    unittest.main()
