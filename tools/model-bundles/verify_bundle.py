#!/usr/bin/env python3
"""Validate a Qualcomm model bundle's manifest and on-disk artifacts."""

import argparse
import hashlib
import json
import re
import stat
import sys
from pathlib import Path, PurePosixPath, PureWindowsPath


class BundleError(ValueError):
    """An actionable validation error caused by bundle input."""


_MANIFEST_KEYS = {
    "schema_version",
    "model_id",
    "model_version",
    "target",
    "qairt_version",
    "voice_ai_version",
    "source",
    "license",
    "artifacts",
}
_TARGET_KEYS = {"device_model", "soc_model", "soc_variant"}
_ARTIFACT_KEYS = {"role", "path", "size_bytes", "sha256"}
_ROLES = {"encoder", "decoder", "vocabulary"}
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def _require_object(value, label):
    if not isinstance(value, dict):
        raise BundleError(f"{label} must be an object")
    return value


def _require_exact_keys(value, expected, label):
    missing = sorted(expected - value.keys())
    extra = sorted(value.keys() - expected)
    if missing:
        raise BundleError(f"{label} is missing required field(s): {', '.join(missing)}")
    if extra:
        raise BundleError(f"{label} contains unsupported metadata: {', '.join(extra)}")


def _require_nonempty_string(value, field):
    if not isinstance(value, str) or not value.strip():
        raise BundleError(f"{field} must be a nonempty string")


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BundleError(f"duplicate metadata key: {key}")
        result[key] = value
    return result


def _safe_relative_path(value):
    if not isinstance(value, str) or not value or "\x00" in value:
        raise BundleError("artifact path must be a safe relative path")
    if "\\" in value:
        raise BundleError("artifact path must use safe relative separators")
    posix_path = PurePosixPath(value)
    windows_path = PureWindowsPath(value)
    if (posix_path.is_absolute() or windows_path.is_absolute() or windows_path.drive
            or str(posix_path) != value or any(part in {"", ".", ".."}
                                               for part in posix_path.parts)):
        raise BundleError("artifact path must be a safe relative path")
    return value


def _validate_manifest(manifest):
    manifest = _require_object(manifest, "manifest")
    _require_exact_keys(manifest, _MANIFEST_KEYS, "manifest")
    schema_version = manifest["schema_version"]
    if isinstance(schema_version, bool) or not isinstance(schema_version, int):
        raise BundleError("schema_version must be integer 1")
    if schema_version != 1:
        raise BundleError(f"unsupported schema_version: {schema_version}")

    for field in ("model_id", "model_version", "qairt_version", "voice_ai_version",
                  "source", "license"):
        _require_nonempty_string(manifest[field], field)

    target = _require_object(manifest["target"], "target")
    _require_exact_keys(target, _TARGET_KEYS, "target")
    expected_target = {
        "device_model": "I2501",
        "soc_model": "SM8850",
        "soc_variant": "V81",
    }
    if target != expected_target:
        raise BundleError("target must be exactly I2501 / SM8850 / V81")

    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != len(_ROLES):
        raise BundleError("artifacts must contain exactly encoder, decoder, and vocabulary")
    seen_roles = set()
    seen_paths = set()
    for index, artifact in enumerate(artifacts):
        artifact = _require_object(artifact, f"artifact {index}")
        _require_exact_keys(artifact, _ARTIFACT_KEYS, f"artifact {index}")
        role = artifact["role"]
        if not isinstance(role, str) or role not in _ROLES:
            raise BundleError(f"artifact {index} has unsupported role: {role!r}")
        if role in seen_roles:
            raise BundleError(f"duplicate artifact role: {role}")
        seen_roles.add(role)

        path = _safe_relative_path(artifact["path"])
        if path in seen_paths:
            raise BundleError(f"duplicate artifact path: {path}")
        seen_paths.add(path)

        size_bytes = artifact["size_bytes"]
        if (isinstance(size_bytes, bool) or not isinstance(size_bytes, int)
                or size_bytes <= 0):
            raise BundleError(f"artifact {role} size_bytes must be a positive integer")
        digest = artifact["sha256"]
        if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
            raise BundleError(f"artifact {role} sha256 must be lowercase hexadecimal")
    if seen_roles != _ROLES:
        raise BundleError("artifacts must contain exactly encoder, decoder, and vocabulary")
    return manifest


def _load_manifest(manifest_path):
    path = Path(manifest_path)
    try:
        with path.open("r", encoding="utf-8") as stream:
            manifest = json.load(stream, object_pairs_hook=_reject_duplicate_keys)
    except json.JSONDecodeError as error:
        raise BundleError(f"malformed manifest JSON: {error.msg}") from None
    except RecursionError:
        raise BundleError("malformed manifest JSON: nesting too deep") from None
    except (OSError, UnicodeError) as error:
        raise BundleError(f"cannot read manifest: {error}") from None
    return _validate_manifest(manifest)


def _bundle_root(bundle_dir):
    try:
        root = Path(bundle_dir).resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise BundleError(f"cannot resolve bundle directory: {error}") from None
    if not root.is_dir():
        raise BundleError("bundle directory is not a directory")
    return root


def _artifact_path(root, relative_path):
    try:
        candidate = (root / relative_path).resolve(strict=False)
        candidate.relative_to(root)
    except (OSError, RuntimeError) as error:
        raise BundleError(f"cannot resolve artifact path {relative_path!r}: {error}") from None
    except ValueError:
        raise BundleError(f"artifact path escapes bundle directory: {relative_path}") from None
    return candidate


def _verify_artifact(root, artifact, seen_file_ids):
    path = _artifact_path(root, artifact["path"])
    try:
        metadata = path.stat()
    except OSError as error:
        raise BundleError(f"artifact file missing: {artifact['path']}") from None
    if not stat.S_ISREG(metadata.st_mode):
        raise BundleError(f"artifact path is not a regular file: {artifact['path']}")
    file_id = (metadata.st_dev, metadata.st_ino)
    if file_id in seen_file_ids:
        raise BundleError(
            f"artifact path aliases the same file as another role: {artifact['path']}"
        )
    seen_file_ids.add(file_id)

    digest = hashlib.sha256()
    actual_size = 0
    try:
        with path.open("rb") as stream:
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                actual_size += len(chunk)
    except OSError as error:
        raise BundleError(f"cannot read artifact {artifact['path']}: {error}") from None

    if actual_size != artifact["size_bytes"]:
        raise BundleError(
            f"artifact {artifact['role']} size bytes mismatch: "
            f"expected {artifact['size_bytes']}, got {actual_size}"
        )
    actual_digest = digest.hexdigest()
    if actual_digest != artifact["sha256"]:
        raise BundleError(f"artifact {artifact['role']} sha256 mismatch")


def verify_bundle(manifest_path, bundle_dir):
    """Validate metadata and all declared files, returning a safe summary."""
    manifest = _load_manifest(manifest_path)
    root = _bundle_root(bundle_dir)
    seen_file_ids = set()
    for artifact in manifest["artifacts"]:
        _verify_artifact(root, artifact, seen_file_ids)
    return {
        "artifact_count": len(manifest["artifacts"]),
        "artifact_integrity_verified": True,
        "model_execution_verified": False,
        "model_id": manifest["model_id"],
        "schema_version": manifest["schema_version"],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify a Qualcomm Whisper model bundle manifest and its artifacts."
    )
    parser.add_argument("manifest_path", help="path to the JSON manifest")
    parser.add_argument("bundle_dir", help="directory containing the declared artifacts")
    args = parser.parse_args(argv)
    try:
        result = verify_bundle(args.manifest_path, args.bundle_dir)
    except (BundleError, OSError, UnicodeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
