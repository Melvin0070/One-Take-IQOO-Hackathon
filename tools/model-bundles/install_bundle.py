#!/usr/bin/env python3
"""Install the pinned Qualcomm Whisper bundle into an atomic versioned store."""

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
from typing import Callable, Iterable
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zipfile import BadZipFile, ZipFile


class InstallError(ValueError):
    """An archive or destination failed validation."""


DOWNLOAD_URL = (
    "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/"
    "qai-hub-models/models/whisper_tiny/releases/v0.61.0/"
    "whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip"
)
MODEL_ID = "qualcomm/Whisper-Tiny"
RELEASE = "v0.61.0"
ARCHIVE_ROOT = "whisper_tiny-voice_ai-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy/"
ARCHIVE_SIZE_BYTES = 106_106_008
ARCHIVE_SHA256 = "086017959cd4e208c0711c0297d8d9d2e5031677082a1e2063651519917f9820"
BUFFER_SIZE = 1024 * 1024
MAX_ZIP_ENTRIES = 16


@dataclass(frozen=True)
class Artifact:
    name: str
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class BundleSpec:
    archive_size_bytes: int
    archive_sha256: str
    artifacts: tuple[Artifact, ...]

    @property
    def artifacts_by_path(self) -> dict[str, Artifact]:
        return {ARCHIVE_ROOT + artifact.name: artifact for artifact in self.artifacts}


SPEC = BundleSpec(
    archive_size_bytes=ARCHIVE_SIZE_BYTES,
    archive_sha256=ARCHIVE_SHA256,
    artifacts=(
        Artifact("encoder.bin", 20_025_344, "c5722aebdce1621e9cddf832b134461a385018a12eabe519a68fad0bcc752f25"),
        Artifact("decoder.bin", 97_615_872, "45418a0c81c8964f2d1448e03f5ce35cd01daa4de19269962fd0414547cccccd"),
        Artifact("vocab.bin", 357_313, "0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b"),
        Artifact("metadata.json", 10_652, "c541446525fef01fdc22cc985260ed701c27e89b0240c608af371c0e4dc91987"),
        Artifact("config.json", 813, "07370a056cd4c78782ee3b96b4b39442b742b31b755f3b229a84289658687b9a"),
    ),
)


def _sha256_file(path: Path, cancel: Callable[[], bool]) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    try:
        with path.open("rb") as stream:
            while True:
                _check_cancel(cancel)
                chunk = stream.read(BUFFER_SIZE)
                if not chunk:
                    break
                digest.update(chunk)
                total += len(chunk)
    except OSError as error:
        raise InstallError(f"cannot read {path}: {error}") from None
    return total, digest.hexdigest()


def _check_cancel(cancel: Callable[[], bool]) -> None:
    if cancel():
        raise InstallError("bundle installation cancelled")


def _validate_archive_file(path: Path, spec: BundleSpec, cancel: Callable[[], bool]) -> Path:
    try:
        path = path.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise InstallError(f"cannot resolve archive: {error}") from None
    if not path.is_file():
        raise InstallError("archive is not a regular file")
    if path.stat().st_size != spec.archive_size_bytes:
        raise InstallError(
            f"archive size mismatch: expected {spec.archive_size_bytes}, "
            f"got {path.stat().st_size}"
        )
    size, digest = _sha256_file(path, cancel)
    if size != spec.archive_size_bytes or digest != spec.archive_sha256:
        raise InstallError("archive SHA-256 mismatch")
    return path


def _safe_entry_name(name: str) -> None:
    if not name or "\x00" in name or "\\" in name:
        raise InstallError("archive contains an unsafe entry path")
    path = PurePosixPath(name)
    if (
        path.is_absolute()
        or name.startswith("/")
        or any(part in {"", ".", ".."} for part in path.parts)
        or not name.startswith(ARCHIVE_ROOT)
    ):
        raise InstallError(f"archive contains an unsafe entry path: {name}")


def _is_symlink(info) -> bool:
    mode = (info.external_attr >> 16) & 0xFFFF
    return stat.S_ISLNK(mode)


def _verify_file(path: Path, artifact: Artifact, cancel: Callable[[], bool]) -> None:
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise InstallError(f"cannot resolve artifact {artifact.name}: {error}") from None
    if resolved != path.absolute() or not resolved.is_file():
        raise InstallError(f"artifact is not a regular file: {artifact.name}")
    size, digest = _sha256_file(resolved, cancel)
    if size != artifact.size_bytes:
        raise InstallError(
            f"artifact size mismatch for {artifact.name}: "
            f"expected {artifact.size_bytes}, got {size}"
        )
    if digest != artifact.sha256:
        raise InstallError(f"artifact SHA-256 mismatch for {artifact.name}")


def _validate_directory(directory: Path, spec: BundleSpec) -> None:
    try:
        root = directory.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise InstallError(f"cannot resolve installed bundle: {error}") from None
    if not root.is_dir():
        raise InstallError("installed bundle is not a directory")
    for artifact in spec.artifacts:
        _verify_file(root / artifact.name, artifact, lambda: False)


def resolve_installed(destination: Path, spec: BundleSpec = SPEC) -> Path | None:
    destination = Path(destination)
    pointer = destination / "active"
    if not pointer.is_file():
        return None
    try:
        name = pointer.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        return None
    if not name.startswith("v-") or len(name) >= 160 or not all(
        char.isalnum() or char in "-_" for char in name
    ):
        return None
    try:
        root = destination.resolve(strict=True)
        candidate = (destination / name).resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    if candidate.parent != root or not candidate.is_dir():
        return None
    try:
        _validate_directory(candidate, spec)
    except InstallError:
        return None
    return candidate


def _extract_archive(
    archive: Path,
    staging: Path,
    spec: BundleSpec,
    cancel: Callable[[], bool],
) -> None:
    expected = spec.artifacts_by_path
    seen: set[str] = set()
    root_seen = False
    try:
        with ZipFile(archive) as source:
            infos = source.infolist()
            if len(infos) > MAX_ZIP_ENTRIES:
                raise InstallError("archive contains too many entries")
            for info in infos:
                _check_cancel(cancel)
                name = info.filename
                _safe_entry_name(name)
                if name in seen:
                    raise InstallError(f"archive contains duplicate entry: {name}")
                seen.add(name)
                if _is_symlink(info):
                    raise InstallError(f"archive contains a symbolic link: {name}")
                if name == ARCHIVE_ROOT:
                    if not info.is_dir():
                        raise InstallError("archive root is not a directory")
                    root_seen = True
                    continue
                artifact = expected.get(name)
                if artifact is None:
                    raise InstallError(f"archive contains unsupported entry: {name}")
                if info.is_dir():
                    raise InstallError(f"artifact is a directory: {name}")
                if info.file_size != artifact.size_bytes:
                    raise InstallError(
                        f"artifact size mismatch for {artifact.name}: "
                        f"expected {artifact.size_bytes}, got {info.file_size}"
                    )
                if info.compress_size > spec.archive_size_bytes:
                    raise InstallError(f"archive entry is too large: {name}")
                output = staging / artifact.name
                try:
                    output.touch(exist_ok=False)
                except FileExistsError:
                    raise InstallError(f"artifact output already exists: {artifact.name}")
                digest = hashlib.sha256()
                total = 0
                try:
                    with source.open(info) as input_stream, output.open("wb") as output_stream:
                        while True:
                            _check_cancel(cancel)
                            chunk = input_stream.read(BUFFER_SIZE)
                            if not chunk:
                                break
                            total += len(chunk)
                            if total > artifact.size_bytes:
                                raise InstallError(f"artifact exceeds its size limit: {artifact.name}")
                            digest.update(chunk)
                            output_stream.write(chunk)
                        output_stream.flush()
                        os.fsync(output_stream.fileno())
                except InstallError:
                    output.unlink(missing_ok=True)
                    raise
                except OSError as error:
                    output.unlink(missing_ok=True)
                    raise InstallError(f"cannot extract artifact {artifact.name}: {error}") from None
                if total != artifact.size_bytes:
                    raise InstallError(f"artifact is incomplete: {artifact.name}")
                if digest.hexdigest() != artifact.sha256:
                    raise InstallError(f"artifact SHA-256 mismatch for {artifact.name}")
    except InstallError:
        raise
    except (BadZipFile, OSError, RuntimeError) as error:
        raise InstallError(f"cannot read archive: {error}") from None
    if not root_seen:
        raise InstallError("archive root directory is missing")
    if seen != expected.keys() | {ARCHIVE_ROOT}:
        missing = sorted(expected.keys() - seen)
        raise InstallError(f"archive is incomplete; missing: {', '.join(missing)}")
    _validate_directory(staging, spec)


def install_archive(
    archive: Path,
    destination: Path,
    spec: BundleSpec = SPEC,
    cancel: Callable[[], bool] = lambda: False,
) -> Path:
    """Verify and atomically install one pinned archive."""
    archive = _validate_archive_file(Path(archive), spec, cancel)
    destination = Path(destination)
    try:
        destination.mkdir(parents=True, exist_ok=True)
        if not destination.is_dir():
            raise InstallError("destination is not a directory")
        destination = destination.resolve()
    except OSError as error:
        raise InstallError(f"cannot create destination: {error}") from None
    existing = resolve_installed(destination, spec)
    if existing is not None:
        return existing

    staging = Path(tempfile.mkdtemp(prefix=".staging-", dir=destination))
    published: Path | None = None
    try:
        _extract_archive(archive, staging, spec, cancel)
        _check_cancel(cancel)
        published = destination / f"v-{uuid.uuid4().hex}"
        os.replace(staging, published)
        _validate_directory(published, spec)
        _check_cancel(cancel)

        pointer_temp = destination / "active.part"
        pointer = destination / "active"
        pointer_temp.unlink(missing_ok=True)
        with pointer_temp.open("w", encoding="utf-8") as output:
            output.write(published.name + "\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(pointer_temp, pointer)
        return published
    except InstallError:
        if published is not None:
            shutil.rmtree(published, ignore_errors=True)
        shutil.rmtree(staging, ignore_errors=True)
        (destination / "active.part").unlink(missing_ok=True)
        raise
    except (OSError, RuntimeError) as error:
        if published is not None:
            shutil.rmtree(published, ignore_errors=True)
        shutil.rmtree(staging, ignore_errors=True)
        (destination / "active.part").unlink(missing_ok=True)
        raise InstallError(f"cannot publish installed bundle: {error}") from None


class _PinnedRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urlparse(newurl)
        if parsed.scheme != "https" or newurl != DOWNLOAD_URL:
            raise InstallError("download redirect is outside the pinned HTTPS release")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def download_pinned_archive(
    destination: Path,
    cancel: Callable[[], bool] = lambda: False,
    timeout_seconds: float = 30.0,
) -> Path:
    """Download the pinned archive with a size cap and return its verified path."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = Request(DOWNLOAD_URL, headers={"Accept-Encoding": "identity", "User-Agent": "OneTake/0.1"})
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=".onetake-download-",
            suffix=".part",
            dir=destination.parent,
            delete=False,
        ) as output:
            temporary = Path(output.name)
            with build_opener(_PinnedRedirectHandler).open(request, timeout=timeout_seconds) as response:
                if response.geturl() != DOWNLOAD_URL:
                    raise InstallError("download did not resolve to the pinned HTTPS release")
                content_length = response.headers.get("Content-Length")
                if content_length is not None and int(content_length) > ARCHIVE_SIZE_BYTES:
                    raise InstallError("download exceeds the pinned archive size")
                digest = hashlib.sha256()
                total = 0
                while True:
                    _check_cancel(cancel)
                    chunk = response.read(BUFFER_SIZE)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > ARCHIVE_SIZE_BYTES:
                        raise InstallError("download exceeds the pinned archive size")
                    digest.update(chunk)
                    output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if total != ARCHIVE_SIZE_BYTES or digest.hexdigest() != ARCHIVE_SHA256:
            raise InstallError("downloaded archive does not match the pinned release")
        _check_cancel(cancel)
        os.replace(temporary, destination)
        temporary = None
        return destination
    except InstallError:
        raise
    except (HTTPError, URLError, OSError, ValueError) as error:
        raise InstallError(f"unable to download pinned archive: {error}") from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--archive", type=Path, help="local pinned Qualcomm Whisper ZIP")
    source.add_argument("--download", action="store_true", help="download the pinned HTTPS release")
    parser.add_argument("--destination", type=Path, required=True, help="versioned bundle store")
    args = parser.parse_args(argv)
    temporary_directory = None
    try:
        if args.download:
            args.destination.parent.mkdir(parents=True, exist_ok=True)
            temporary_directory = tempfile.TemporaryDirectory(
                prefix=".onetake-download-",
                dir=args.destination.parent,
            )
            archive = download_pinned_archive(Path(temporary_directory.name) / "archive.zip")
        else:
            archive = args.archive
        installed = install_archive(archive, args.destination)
        print(json.dumps({
            "artifact_integrity_verified": True,
            "installed_directory": str(installed),
            "model_id": MODEL_ID,
            "release": RELEASE,
        }, sort_keys=True))
        return 0
    except (InstallError, OSError, UnicodeError, ValueError) as error:
        print(f"error: {error}")
        return 2
    finally:
        if temporary_directory is not None:
            temporary_directory.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
