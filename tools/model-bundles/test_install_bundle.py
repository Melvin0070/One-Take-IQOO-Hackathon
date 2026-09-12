import hashlib
import io
from pathlib import Path
import shutil
import tempfile
from contextlib import redirect_stdout
from unittest.mock import patch
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

    def test_download_failure_preserves_existing_part_file(self):
        destination = self.root / "download.zip"
        sentinel = destination.with_name(destination.name + ".part")
        sentinel.write_bytes(b"keep this unrelated file")

        with patch.object(install_bundle, "build_opener", return_value=FailingOpener()):
            with self.assertRaisesRegex(install_bundle.InstallError, "unable to download"):
                install_bundle.download_pinned_archive(destination)

        self.assertEqual(b"keep this unrelated file", sentinel.read_bytes())

    def test_main_download_failure_preserves_existing_sibling(self):
        destination = self.root / "store"
        sentinel = destination.with_name(destination.name + ".download.zip")
        sentinel.write_bytes(b"keep this unrelated file")

        with patch.object(install_bundle, "build_opener", return_value=FailingOpener()):
            with redirect_stdout(io.StringIO()):
                result = install_bundle.main(["--download", "--destination", str(destination)])

        self.assertEqual(2, result)
        self.assertEqual(b"keep this unrelated file", sentinel.read_bytes())

    def test_download_cancellation_before_publish_preserves_destination(self):
        payload = b"verified archive"
        destination = self.root / "download.zip"
        destination.write_bytes(b"previous archive")
        checks = 0

        def cancel():
            nonlocal checks
            checks += 1
            return checks >= 3

        with patch.object(install_bundle, "ARCHIVE_SIZE_BYTES", len(payload)), \
                patch.object(install_bundle, "ARCHIVE_SHA256", hashlib.sha256(payload).hexdigest()), \
                patch.object(install_bundle, "build_opener", return_value=PayloadOpener(payload)):
            with self.assertRaisesRegex(install_bundle.InstallError, "cancelled"):
                install_bundle.download_pinned_archive(destination, cancel=cancel)

        self.assertEqual(b"previous archive", destination.read_bytes())
        self.assertGreaterEqual(checks, 3)

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

class FailingOpener:
    def open(self, request, timeout):
        raise install_bundle.URLError("test download failure")


class PayloadOpener:
    def __init__(self, payload):
        self.payload = payload

    def open(self, request, timeout):
        return PayloadResponse(self.payload)


class PayloadResponse:
    def __init__(self, payload):
        self.payload = payload
        self.reads = 0
        self.headers = {"Content-Length": str(len(payload))}

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def geturl(self):
        return install_bundle.DOWNLOAD_URL

    def read(self, size):
        self.reads += 1
        if self.reads == 1:
            return self.payload
        return b""


if __name__ == "__main__":
    unittest.main()
