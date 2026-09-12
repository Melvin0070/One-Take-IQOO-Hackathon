#!/usr/bin/env python3
"""Run the QAIRT converter model on an Android HTP target.

The smoke test intentionally uses the model and converter wrapper sources
shipped by QAIRT.  It compiles those sources with the local Android NDK,
stages the resulting model and the requested HTP runtime in a unique
``/data/local/tmp`` directory, and removes only that directory when finished.

This is a validation tool, not a portable NPU abstraction.  A successful run
means that QNN selected the requested HTP backend and produced outputs and
profiling data.  It does not claim that a device exposes a generic NPU
utilization counter.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tarfile
import uuid
from typing import Any, Sequence


MODEL_NAME = "qnn_model_8bit_quantized"
ANDROID_TARGET = "aarch64-linux-android24"
REMOTE_PREFIX = "/data/local/tmp/device-monitor-qnn-"
DEFAULT_SDK = os.environ.get("QAIRT_SDK_ROOT")
DEFAULT_NDK = os.environ.get("ANDROID_NDK_ROOT")
DEFAULT_SERIAL = os.environ.get("ANDROID_SERIAL")
DEFAULT_ADB = os.environ.get("ADB")


class SmokeError(RuntimeError):
    """An expected smoke-test failure with an actionable message."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(path: Path, description: str) -> Path:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise SmokeError(f"{description} does not exist: {path}")
    return path


def require_directory(path: Path, description: str) -> Path:
    path = path.expanduser().resolve()
    if not path.is_dir():
        raise SmokeError(f"{description} does not exist: {path}")
    return path


def resolve_adb(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        requested = Path(explicit).expanduser()
        resolved = shutil.which(explicit) if requested.parent == Path(".") else None
        candidates.append(Path(resolved) if resolved else requested)
    else:
        if DEFAULT_ADB:
            candidates.append(Path(DEFAULT_ADB).expanduser())
        found = shutil.which("adb")
        if found:
            candidates.append(Path(found))
        candidates.extend([
            Path.home() / "Library/Android/sdk/platform-tools/adb",
            Path.home() / "Android/Sdk/platform-tools/adb",
        ])
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    requested = f" {explicit!r}" if explicit else ""
    raise SmokeError(
        f"Could not find an executable adb{requested}. Pass --adb or install Android platform-tools."
    )


def resolve_host_tag(ndk: Path) -> str:
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    system = platform.system().lower()
    machine = platform.machine().lower()
    candidates: list[str] = []
    if system == "darwin":
        candidates.extend(["darwin-arm64", "darwin-x86_64"])
    elif system == "linux":
        candidates.extend(["linux-x86_64", "linux-arm64"])
    elif system == "windows":
        candidates.extend(["windows-x86_64"])
    if machine in {"x86_64", "amd64"}:
        candidates.extend(["darwin-x86_64", "linux-x86_64", "windows-x86_64"])
    elif machine in {"arm64", "aarch64"}:
        candidates.extend(["darwin-arm64", "linux-arm64"])
    candidates.extend(path.name for path in sorted(prebuilt.glob("*")) if path.is_dir())
    seen: set[str] = set()
    for tag in candidates:
        if tag in seen:
            continue
        seen.add(tag)
        if (prebuilt / tag / "bin/clang++").is_file():
            return tag
    raise SmokeError(f"NDK host toolchain was not found under {prebuilt}")


@dataclass(frozen=True)
class Toolchain:
    sdk: Path
    ndk: Path
    host_tag: str
    clang: Path
    objcopy: Path
    nm: Path
    sysroot: Path
    libcxx: Path


def resolve_toolchain(sdk: Path, ndk: Path) -> Toolchain:
    sdk = require_directory(sdk, "QAIRT SDK")
    ndk = require_directory(ndk, "Android NDK")
    host_tag = resolve_host_tag(ndk)
    bin_dir = ndk / "toolchains/llvm/prebuilt" / host_tag / "bin"
    sysroot = ndk / "toolchains/llvm/prebuilt" / host_tag / "sysroot"
    # Keep the clang++ driver spelling.  Resolving this symlink to clang-21
    # makes the driver treat the link as a C invocation, silently ignoring
    # ``-static-libstdc++`` and leaving the model's C++ symbols unresolved.
    clang_path = bin_dir / "clang++"
    require_file(clang_path, "NDK clang++")
    clang = clang_path
    objcopy = require_file(bin_dir / "llvm-objcopy", "NDK llvm-objcopy")
    nm = require_file(bin_dir / "llvm-nm", "NDK llvm-nm")
    libcxx = require_file(
        sysroot / "usr/lib/aarch64-linux-android/libc++_shared.so",
        "NDK libc++_shared.so",
    )
    return Toolchain(sdk, ndk, host_tag, clang, objcopy, nm, sysroot, libcxx)


def qnn_paths(sdk: Path) -> dict[str, Path]:
    model_root = sdk / "examples/QNN/converter/models"
    jni_root = sdk / "share/QNN/converter/jni"
    android_lib = sdk / "lib/aarch64-android"
    hexagon_lib = sdk / "lib/hexagon-v81/unsigned"
    paths = {
        "model_cpp": model_root / f"{MODEL_NAME}.cpp",
        "model_bin": model_root / f"{MODEL_NAME}.bin",
        "input_list": model_root / "input_list_float.txt",
        "input_dir": model_root / "input_data_float",
        "qnn_model_cpp": jni_root / "QnnModel.cpp",
        "qnn_wrapper_cpp": jni_root / "QnnWrapperUtils.cpp",
        "qnn_pal_cpp": jni_root / "linux/QnnModelPal.cpp",
        "qnn_include": sdk / "include/QNN",
        "qnn_jni_include": jni_root,
        "runner": sdk / "bin/aarch64-android/qnn-net-run",
        "profile_viewer": sdk / "bin/aarch64-android/qnn-profile-viewer",
        "htp": android_lib / "libQnnHtp.so",
        "htp_prepare": android_lib / "libQnnHtpPrepare.so",
        "htp_stub": android_lib / "libQnnHtpV81Stub.so",
        "htp_skel": hexagon_lib / "libQnnHtpV81Skel.so",
        "htp_reader": android_lib / "libQnnHtpProfilingReader.so",
        "chrome_reader": android_lib / "libQnnChrometraceProfilingReader.so",
        "cpu": android_lib / "libQnnCpu.so",
    }
    for name, path in paths.items():
        if name == "input_dir":
            require_directory(path, f"QAIRT {name}")
        elif name in {"qnn_include", "qnn_jni_include"}:
            require_directory(path, f"QAIRT {name}")
        else:
            require_file(path, f"QAIRT {name}")
    return paths


def extract_binary_weights(binary: Path, destination: Path) -> list[Path]:
    """Extract only flat raw members from the converter tar archive."""

    destination.mkdir(parents=True, exist_ok=True)
    extracted: list[Path] = []
    try:
        archive = tarfile.open(binary, "r:*")
    except (OSError, tarfile.TarError) as exc:
        raise SmokeError(f"Cannot open converter binary archive {binary}: {exc}") from exc
    with archive:
        for member in archive.getmembers():
            member_path = PurePosixPath(member.name)
            if member.isdir():
                continue
            if not member.isfile() or len(member_path.parts) != 1 or member_path.suffix != ".raw":
                raise SmokeError(f"Unexpected converter binary member: {member.name}")
            source = archive.extractfile(member)
            if source is None:
                raise SmokeError(f"Could not read converter binary member: {member.name}")
            target = destination / member_path.name
            with target.open("wb") as stream:
                shutil.copyfileobj(source, stream)
            extracted.append(target)
    if not extracted:
        raise SmokeError(f"Converter binary archive contains no raw weights: {binary}")
    return sorted(extracted)


def _command_text(command: Sequence[str]) -> str:
    return shlex.join(str(part) for part in command)


def run_host_command(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    log_path: Path | None = None,
    log_label: str = "command",
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )
    if log_path is not None:
        with log_path.open("a", encoding="utf-8") as stream:
            stream.write(f"$ {_command_text(command)}\n")
            if result.stdout:
                stream.write(result.stdout)
            if result.stderr:
                stream.write(result.stderr)
            stream.write(f"[{log_label} exit={result.returncode}]\n\n")
    if check and result.returncode:
        detail = (result.stderr or result.stdout or "command failed").strip()
        raise SmokeError(f"{log_label} failed ({result.returncode}): {detail}")
    return result


def build_model(toolchain: Toolchain, paths: dict[str, Path], build_dir: Path) -> tuple[Path, dict[str, str]]:
    """Compile the supplied model and JNI wrapper sources for arm64 Android."""

    build_dir.mkdir(parents=True, exist_ok=False)
    log_path = build_dir / "build.log"
    binary_dir = build_dir / "obj/binary"
    object_dir = build_dir / "obj"
    binary_raws = extract_binary_weights(paths["model_bin"], binary_dir)
    include_args = [f"-I{paths['qnn_include']}", f"-I{paths['qnn_jni_include']}"]
    common = [
        str(toolchain.clang),
        f"--target={ANDROID_TARGET}",
        f"--sysroot={toolchain.sysroot}",
        "-std=gnu++17",
        "-fPIC",
        "-fvisibility=hidden",
        "-O3",
        "-Wno-write-strings",
        '-DQNN_API=__attribute__((visibility("default")))',
        *include_args,
    ]
    sources = [
        ("model", paths["model_cpp"]),
        ("qnn-model", paths["qnn_model_cpp"]),
        ("qnn-wrapper", paths["qnn_wrapper_cpp"]),
        ("qnn-pal", paths["qnn_pal_cpp"]),
    ]
    objects: list[Path] = []
    source_hashes: dict[str, str] = {}
    for label, source in sources:
        source_hashes[str(source)] = sha256_file(source)
        object_path = object_dir / f"{label}.o"
        object_path.parent.mkdir(parents=True, exist_ok=True)
        run_host_command(
            [*common, "-c", str(source), "-o", str(object_path)],
            cwd=build_dir,
            log_path=log_path,
            log_label=f"compile {label}",
        )
        objects.append(object_path)

    binary_objects: list[Path] = []
    for raw in binary_raws:
        # Run objcopy from the build root with the same relative obj/binary
        # path used by the supplied Android.mk.  Absolute paths would change
        # the generated _binary_obj_binary_* symbols expected by the source.
        relative_raw = raw.relative_to(build_dir).as_posix()
        relative_object = relative_raw[:-4] + ".o"
        run_host_command(
            [str(toolchain.objcopy), "-I", "binary", "-O", "elf64-littleaarch64", "-B", "aarch64",
             relative_raw, relative_object],
            cwd=build_dir,
            log_path=log_path,
            log_label=f"objcopy {raw.name}",
        )
        binary_objects.append(build_dir / relative_object)

    model_library = build_dir / f"lib{MODEL_NAME}.so"
    link_command = [
        str(toolchain.clang),
        f"--target={ANDROID_TARGET}",
        f"--sysroot={toolchain.sysroot}",
        "-shared",
        "-fPIC",
        "-static-libstdc++",
        "-Wl,--no-undefined",
        f"-Wl,-soname,lib{MODEL_NAME}.so",
        "-o",
        str(model_library),
        *(str(path) for path in objects),
        *(str(path) for path in binary_objects),
    ]
    run_host_command(link_command, cwd=build_dir, log_path=log_path, log_label="link model")
    exported = run_host_command(
        [str(toolchain.nm), "-D", str(model_library)],
        log_path=log_path,
        log_label="inspect model exports",
    ).stdout
    if " QnnModel_composeGraphs" not in exported and " T QnnModel_composeGraphs" not in exported:
        raise SmokeError(f"Compiled model does not export QnnModel_composeGraphs: {model_library}")
    source_hashes[str(paths["model_bin"])] = sha256_file(paths["model_bin"])
    return model_library, source_hashes


@dataclass
class Adb:
    path: Path
    serial: str

    def run(
        self,
        *args: str,
        timeout: float = 120,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        command = [str(self.path), "-s", self.serial, *args]
        try:
            result = subprocess.run(command, capture_output=True, text=True,
                                    timeout=timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise SmokeError(f"ADB command timed out: {_command_text(command)}") from exc
        if check and result.returncode:
            detail = (result.stderr or result.stdout or "command failed").strip()
            raise SmokeError(f"ADB command failed ({result.returncode}): {detail}")
        return result

    def shell(self, script: str, *, timeout: float = 120, check: bool = True) -> subprocess.CompletedProcess[str]:
        # Pass the complete command as one argument.  Recent platform-tools
        # already invoke the device shell for this form; splitting it through
        # ``sh -c`` makes the command after ``-c`` lose its spaces on some
        # Android shell implementations (for example, ``mkdir -p`` becomes a
        # zero-argument mkdir).
        return self.run("shell", script, timeout=timeout, check=check)

    def push(self, source: Path, target: str, *, timeout: float = 120) -> None:
        self.run("push", str(source), target, timeout=timeout)

    def pull(self, source: str, target: Path, *, timeout: float = 120) -> None:
        self.run("pull", source, str(target), timeout=timeout)


def validate_device(adb: Adb) -> None:
    result = adb.run("get-state", check=False)
    if result.returncode or result.stdout.strip() != "device":
        detail = (result.stderr or result.stdout or "not ready").strip()
        raise SmokeError(f"ADB device {adb.serial!r} is not ready: {detail}")


def new_remote_root() -> str:
    return REMOTE_PREFIX + uuid.uuid4().hex


def assert_owned_remote(path: str) -> None:
    if not re.fullmatch(re.escape(REMOTE_PREFIX) + r"[0-9a-f]{32}", path):
        raise SmokeError(f"Refusing to operate on an unowned remote path: {path}")


def remote_path(root: str, *parts: str) -> str:
    assert_owned_remote(root)
    for part in parts:
        if part in {".", ".."} or not re.fullmatch(r"[A-Za-z0-9_+.-]+", part):
            raise SmokeError(f"Invalid remote path component: {part}")
    return "/".join((root, *parts))


def stage_runtime(adb: Adb, root: str, paths: dict[str, Path], model_library: Path,
                  libcxx: Path, compare_cpu: bool) -> dict[str, str]:
    adb.shell(f"mkdir -p {shlex.quote(root)}")
    files = {
        "qnn-net-run": paths["runner"],
        "qnn-profile-viewer": paths["profile_viewer"],
        "libc++_shared.so": libcxx,
        f"lib{MODEL_NAME}.so": model_library,
        "libQnnHtp.so": paths["htp"],
        "libQnnHtpPrepare.so": paths["htp_prepare"],
        "libQnnHtpV81Stub.so": paths["htp_stub"],
        "libQnnHtpV81Skel.so": paths["htp_skel"],
        "libQnnHtpProfilingReader.so": paths["htp_reader"],
        "libQnnChrometraceProfilingReader.so": paths["chrome_reader"],
    }
    if compare_cpu:
        files["libQnnCpu.so"] = paths["cpu"]
    local_hashes: dict[str, str] = {}
    for name, source in files.items():
        adb.push(source, remote_path(root, name))
        local_hashes[name] = sha256_file(source)
    adb.push(paths["input_list"], remote_path(root, "input_list_float.txt"))
    adb.push(paths["input_dir"], remote_path(root, "input_data_float"))
    adb.shell(f"chmod 755 {shlex.quote(remote_path(root, 'qnn-net-run'))} "
              f"{shlex.quote(remote_path(root, 'qnn-profile-viewer'))}")
    return local_hashes


def remote_environment(root: str) -> str:
    root_q = shlex.quote(root)
    return (
        f"export LD_LIBRARY_PATH={root_q}:/vendor/lib64:/vendor/lib; "
        f"export ADSP_LIBRARY_PATH={root_q}:/vendor/dsp/cdsp:/vendor/lib/rfsa/adsp:"
        "/system/lib/rfsa/adsp:/dsp; "
        f"cd {root_q}; "
    )


@dataclass
class BackendRun:
    backend: str
    output_name: str
    result: subprocess.CompletedProcess[str]
    log_text: str
    output_pulled: bool = False

    @property
    def succeeded(self) -> bool:
        return self.result.returncode == 0


def run_backend(adb: Adb, root: str, backend: str, output_name: str,
                input_count: int, local_log: Path) -> BackendRun:
    backend_library = "libQnnHtp.so" if backend == "htp" else "libQnnCpu.so"
    command = [
        "./qnn-net-run",
        f"--model=lib{MODEL_NAME}.so",
        "--input_list=input_list_float.txt",
        f"--backend={backend_library}",
        f"--output_dir={output_name}",
        "--profiling_level=detailed",
        f"--num_inferences={input_count}",
        "--log_level=info",
    ]
    script = remote_environment(root) + f"mkdir -p {shlex.quote(output_name)}; " \
        + "exec timeout -k 5s 240s " + _command_text(command)
    result = adb.shell(script, timeout=300, check=False)
    log_text = (
        f"backend={backend_library}\n"
        f"command={_command_text(command)}\n"
        f"exit_code={result.returncode}\n"
        f"{result.stdout or ''}{result.stderr or ''}"
    )
    local_log.write_text(log_text, encoding="utf-8")
    return BackendRun(backend, output_name, result, log_text)


def pull_directory(adb: Adb, root: str, remote_name: str, local: Path) -> None:
    local.mkdir(parents=True, exist_ok=False)
    adb.pull(remote_path(root, remote_name) + "/.", local)


def validate_output_directory(path: Path) -> list[Path]:
    if not path.is_dir():
        return []
    files = [item for item in path.rglob("*") if item.is_file()
             and not item.name.startswith("qnn-profiling-data")]
    if not files:
        return []
    if any(item.stat().st_size == 0 for item in files):
        raise SmokeError(f"QNN output contains an empty file: {path}")
    return sorted(files)


def remote_profile_logs(adb: Adb, root: str, output_name: str) -> list[str]:
    directory = remote_path(root, output_name)
    result = adb.shell(
        f"find {shlex.quote(directory)} -maxdepth 1 -type f "
        "-name 'qnn-profiling-data*.log' -print",
        check=False,
    )
    if result.returncode:
        return []
    logs: list[str] = []
    for line in (result.stdout or "").splitlines():
        candidate = line.strip()
        if not candidate.startswith(directory + "/"):
            continue
        if re.fullmatch(r"qnn-profiling-data(?:_[0-9]+)?\.log", candidate.rsplit("/", 1)[-1]):
            logs.append(candidate)
    return sorted(logs)


def run_profile_viewer(adb: Adb, root: str, profile_logs: list[str],
                       output_name: str, reader: str, local_stdout: Path,
                       local_output: Path) -> dict[str, Any]:
    if not profile_logs:
        local_stdout.write_text("No qnn-profiling-data*.log was produced.\n", encoding="utf-8")
        return {"status": "missing_log", "reader": reader}
    remote_logs = ",".join(path[len(root) + 1:] for path in profile_logs)
    command = [
        "./qnn-profile-viewer",
        f"--input_log={remote_logs}",
        f"--reader={reader}",
        f"--output={output_name}",
    ]
    script = remote_environment(root) + "exec " + _command_text(command)
    result = adb.shell(script, timeout=300, check=False)
    text = (result.stdout or "") + (result.stderr or "")
    local_stdout.write_text(
        f"reader={reader}\ncommand={_command_text(command)}\nexit_code={result.returncode}\n{text}",
        encoding="utf-8",
    )
    if result.returncode:
        return {"status": "failed", "reader": reader, "exit_code": result.returncode}
    try:
        adb.pull(remote_path(root, output_name), local_output)
    except SmokeError as exc:
        return {"status": "missing_output", "reader": reader, "error": str(exc)}
    if not local_output.is_file() or local_output.stat().st_size == 0:
        return {"status": "empty_output", "reader": reader}
    return {"status": "ok", "reader": reader, "bytes": local_output.stat().st_size}


def verify_htp_execution(log_text: str, profile_text: str) -> dict[str, Any]:
    request_text = log_text.lower()
    runtime_text = profile_text.lower()
    cpu_markers = ("libqnncpu.so", "qnn cpu", "cpu backend")
    htp_markers = ("libqnnhtp.so", "qnnhtp", "qnn htp", "htp backend", "hexagon")
    return {
        "requested_backend": "libQnnHtp.so",
        "backend_request_present": any(marker in request_text for marker in htp_markers),
        "runtime_htp_marker_present": any(marker in runtime_text for marker in htp_markers),
        "cpu_fallback_marker_present": any(
            marker in request_text or marker in runtime_text for marker in cpu_markers
        ),
        "npu_utilization": "not measured by this smoke test",
    }


def _float_values(path: Path) -> list[float] | None:
    data = path.read_bytes()
    if len(data) == 0 or len(data) % 4:
        return None
    values = list(struct.unpack(f"<{len(data) // 4}f", data))
    if not all(math.isfinite(value) for value in values):
        raise SmokeError(f"Tensor output contains a non-finite float: {path}")
    return values


def compare_output_files(before: Path, after: Path) -> dict[str, Any]:
    before_files = {
        path.relative_to(before).as_posix(): path
        for path in before.rglob("*")
        if path.is_file() and path.suffix.lower() == ".raw"
    }
    after_files = {
        path.relative_to(after).as_posix(): path
        for path in after.rglob("*")
        if path.is_file() and path.suffix.lower() == ".raw"
    }
    common = sorted(set(before_files) & set(after_files))
    results: list[dict[str, Any]] = []
    for name in common:
        left, right = before_files[name], after_files[name]
        entry: dict[str, Any] = {"file": name, "bytes_before": left.stat().st_size,
                                 "bytes_after": right.stat().st_size}
        if left.stat().st_size != right.stat().st_size:
            raise SmokeError(f"Tensor output size differs for {name}")
        left_values, right_values = _float_values(left), _float_values(right)
        if left_values is not None and right_values is not None:
            differences = [abs(a - b) for a, b in zip(left_values, right_values)
                           ]
            entry.update({
                "representation": "float32",
                "elements": len(left_values),
                "max_abs_error": max(differences, default=0.0),
                "mean_abs_error": sum(differences) / len(differences) if differences else None,
                "within_1e-3": max(differences, default=0.0) <= 1e-3,
            })
        else:
            entry.update({"representation": "bytes", "identical": left.read_bytes() == right.read_bytes()})
        results.append(entry)
    comparison = {
        "before": str(before),
        "after": str(after),
        "common_files": len(common),
        "only_before": sorted(set(before_files) - set(after_files)),
        "only_after": sorted(set(after_files) - set(before_files)),
        "files": results,
    }
    comparison["status"] = (
        "passed"
        if common and all(
            item.get("within_1e-3", item.get("identical", False)) for item in results
        )
        else "failed"
    )
    return comparison


def default_output_dir() -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return Path(__file__).resolve().parent / "runs" / f"qnn-{stamp}-{uuid.uuid4().hex[:8]}"


def create_output_dir(path: Path) -> Path:
    path = path.expanduser().resolve()
    if path.exists():
        raise SmokeError(f"Refusing to overwrite existing output directory: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.mkdir()
    return path


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def run_smoke(
    sdk: Path,
    ndk: Path,
    serial: str,
    output_dir: Path,
    *,
    adb_path: str | None = None,
    compare_cpu: bool = False,
    keep_remote: bool = False,
) -> dict[str, Any]:
    output = create_output_dir(output_dir)
    remote: str | None = None
    manifest: dict[str, Any] = {
        "tool": "qnn_smoke",
        "status": "failed",
        "started_at": datetime.now(timezone.utc).isoformat(),
        "sdk": str(sdk.expanduser().resolve()),
        "ndk": str(ndk.expanduser().resolve()),
        "serial": serial,
        "output_dir": str(output),
        "compare_cpu": compare_cpu,
    }
    try:
        toolchain = resolve_toolchain(sdk, ndk)
        paths = qnn_paths(toolchain.sdk)
        adb = Adb(resolve_adb(adb_path), serial)
        validate_device(adb)
        build_dir = output / "build"
        model_library, source_hashes = build_model(toolchain, paths, build_dir)
        manifest["host_tag"] = toolchain.host_tag
        manifest["source_sha256"] = source_hashes
        manifest["model_library_sha256"] = sha256_file(model_library)
        remote = new_remote_root()
        assert_owned_remote(remote)
        manifest["remote_root"] = remote
        manifest["staged_sha256"] = stage_runtime(adb, remote, paths, model_library,
                                                   toolchain.libcxx, compare_cpu)

        input_count = len([line for line in paths["input_list"].read_text().splitlines() if line.strip()])
        htp_log = output / "htp-run.log"
        htp_run = run_backend(adb, remote, "htp", "htp-output", input_count, htp_log)
        try:
            pull_directory(adb, remote, "htp-output", output / "htp-output")
            htp_run.output_pulled = True
        except SmokeError as exc:
            manifest["htp_output_pull_error"] = str(exc)
        htp_files = validate_output_directory(output / "htp-output")
        if not htp_run.succeeded or not htp_files:
            raise SmokeError(
                "HTP execution failed or produced no tensor output. "
                f"See {htp_log}."
            )

        profile_logs = remote_profile_logs(adb, remote, "htp-output")
        profile_stdout = output / "htp-profile-viewer.log"
        profile_csv = output / "htp-profile.csv"
        profile_result = run_profile_viewer(
            adb, remote, profile_logs, "htp-profile.csv", "libQnnHtpProfilingReader.so",
            profile_stdout, profile_csv,
        )
        chrome_stdout = output / "htp-chrome-profile-viewer.log"
        chrome_json = output / "htp-chrome-trace.json"
        chrome_result = run_profile_viewer(
            adb, remote, profile_logs, "htp-chrome-trace.json",
            "libQnnChrometraceProfilingReader.so", chrome_stdout, chrome_json,
        )
        if profile_result.get("status") != "ok":
            raise SmokeError(
                "Detailed HTP profiling output was not generated. "
                f"See {profile_stdout}."
            )
        profile_text = profile_stdout.read_text(encoding="utf-8", errors="replace")
        if profile_csv.is_file():
            profile_text += "\n" + profile_csv.read_text(encoding="utf-8", errors="replace")
        execution = verify_htp_execution(htp_run.log_text, profile_text)
        if execution["cpu_fallback_marker_present"]:
            raise SmokeError("HTP run log contains a CPU backend marker; refusing to report it as NPU execution.")
        manifest["execution"] = execution
        manifest["profiling"] = {"csv": profile_result, "chrome_trace": chrome_result,
                                  "log_count": len(profile_logs)}

        if compare_cpu:
            cpu_log = output / "cpu-run.log"
            cpu_run = run_backend(adb, remote, "cpu", "cpu-output", input_count, cpu_log)
            try:
                pull_directory(adb, remote, "cpu-output", output / "cpu-output")
                cpu_run.output_pulled = True
            except SmokeError as exc:
                manifest["cpu_output_pull_error"] = str(exc)
            cpu_files = validate_output_directory(output / "cpu-output")
            if not cpu_run.succeeded or not cpu_files:
                raise SmokeError(f"CPU comparison run failed or produced no output. See {cpu_log}.")
            comparison = compare_output_files(output / "htp-output", output / "cpu-output")
            write_json(output / "output-comparison.json", comparison)
            manifest["cpu_comparison"] = comparison

        manifest["status"] = "passed"
        manifest["finished_at"] = datetime.now(timezone.utc).isoformat()
        return manifest
    except (OSError, SmokeError, ValueError, subprocess.SubprocessError) as exc:
        manifest["error"] = str(exc)
        manifest["finished_at"] = datetime.now(timezone.utc).isoformat()
        return manifest
    finally:
        if remote is not None:
            try:
                assert_owned_remote(remote)
                if keep_remote:
                    manifest["remote_cleanup"] = "kept_by_request"
                else:
                    # The path is generated and validated above; this command
                    # can only remove this smoke test's own directory.
                    adb.shell(f"rm -rf {shlex.quote(remote)}", check=True)
                    manifest["remote_cleanup"] = "removed"
            except (SmokeError, OSError) as exc:
                manifest["remote_cleanup"] = "failed"
                manifest["remote_cleanup_error"] = str(exc)
        try:
            write_json(output / "manifest.json", manifest)
        except OSError:
            pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(DEFAULT_SDK) if DEFAULT_SDK else None,
                        required=DEFAULT_SDK is None,
                        help="QAIRT SDK root (or QAIRT_SDK_ROOT)")
    parser.add_argument("--ndk", type=Path, default=Path(DEFAULT_NDK) if DEFAULT_NDK else None,
                        required=DEFAULT_NDK is None,
                        help="Android NDK root (or ANDROID_NDK_ROOT)")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, required=DEFAULT_SERIAL is None,
                        help="ADB serial of the Android device (or ANDROID_SERIAL)")
    parser.add_argument("--adb", help="ADB executable path (or ADB)")
    parser.add_argument("--output-dir", type=Path, default=None,
                        help="New local output directory; defaults to runs/qnn-<timestamp>-<uuid>")
    parser.add_argument("--compare-cpu", action="store_true",
                        help="Run the same quantized model on QNN CPU and compare raw outputs")
    parser.add_argument("--keep-remote", action="store_true",
                        help="Keep the unique remote staging directory for debugging")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    output = args.output_dir or default_output_dir()
    try:
        manifest = run_smoke(
            args.sdk,
            args.ndk,
            args.serial,
            output,
            adb_path=args.adb,
            compare_cpu=args.compare_cpu,
            keep_remote=args.keep_remote,
        )
    except (OSError, SmokeError, ValueError) as exc:
        print(f"qnn-smoke: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, indent=2, allow_nan=False))
    return 0 if manifest.get("status") == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
