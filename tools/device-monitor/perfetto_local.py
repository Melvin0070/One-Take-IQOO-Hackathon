#!/usr/bin/env python3
"""Install an unchanged official Perfetto UI build, capture Android traces, and serve locally."""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from urllib.parse import quote, unquote, urlsplit

from device_monitor import AdbClient, MonitorError, discover_adb, select_device


VERSION = 'v58.3-11fbaed83'
ORIGIN = 'https://ui.perfetto.dev'
CACHE = Path(__file__).resolve().parent / '.cache' / 'perfetto'


def resource_path(root, name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or '..' in path.parts or '\\' in name
            or ':' in name or '?' in name or '#' in name):
        raise ValueError(f'Invalid resource path: {name}')
    return Path(root).joinpath(*path.parts)


def verify_resource(data, digest):
    actual = 'sha256-' + base64.b64encode(hashlib.sha256(data).digest()).decode()
    if actual != digest:
        raise ValueError('Perfetto resource checksum mismatch')


def download(url):
    # curl uses the host's TLS trust store; never disable certificate validation.
    result = subprocess.run(
        ['curl', '--fail', '--silent', '--show-error', '--location', '--proto', '=https',
         '--max-time', '120', '--retry', '2', url], capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f'Download failed: {url}: {result.stderr.decode(errors="replace")}')
    return result.stdout


def install(version=VERSION, cache=CACHE):
    if not re.fullmatch(r'v\d+\.\d+-[a-f0-9]+', version):
        raise ValueError('Expected an official version such as ' + VERSION)
    if shutil.which('curl') is None:
        raise RuntimeError('curl is required to install the official UI assets.')
    root = Path(cache) / version
    root.mkdir(parents=True, exist_ok=True)
    # A failed repair must never advertise an incomplete installation as ready.
    (root / '.ready').unlink(missing_ok=True)
    base = f'{ORIGIN}/{version}/'
    manifest_bytes = download(base + 'manifest.json')
    manifest = json.loads(manifest_bytes)
    resources = manifest['resources']

    def fetch_resource(item):
        name, digest = item
        target = resource_path(root, name)
        if target.is_file():
            try:
                verify_resource(target.read_bytes(), digest)
                return
            except ValueError:
                pass
        data = download(base + name)
        verify_resource(data, digest)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    print(f'Installing official Perfetto {version}: {len(resources)} verified assets', flush=True)
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(fetch_resource, resources.items()))
    # Keep upstream generated files unchanged, including its pinned-version bootstrap.
    (root / 'index.html').write_bytes(download(base))
    (root / 'manifest.json').write_bytes(manifest_bytes)
    # The shared upstream service worker is optional for offline caching, not UI execution.
    (root / 'service_worker.js').write_bytes(download(ORIGIN + '/service_worker.js'))
    (root / '.ready').write_text(version)
    print(f'Installed to {root}', flush=True)
    return root


def handler_for(root, trace=None):
    root = Path(root).resolve()
    trace = Path(trace).resolve() if trace else None

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            route = unquote(urlsplit(self.path).path)
            if route == '/trace' and trace is not None:
                target, mime = trace, 'application/octet-stream'
            else:
                name = 'index.html' if route == '/' else route.lstrip('/')
                try:
                    target = resource_path(root, name).resolve()
                    target.relative_to(root)
                except ValueError:
                    self.send_error(404)
                    return
                mime = {'.wasm': 'application/wasm', '.js': 'text/javascript'}.get(
                    target.suffix, mimetypes.guess_type(target.name)[0] or 'application/octet-stream')
            if not target.is_file() or target.name.startswith('.'):
                self.send_error(404)
                return
            try:
                with target.open('rb') as stream:
                    self.send_response(200)
                    self.send_header('Content-Type', mime)
                    self.send_header('Content-Length', str(target.stat().st_size))
                    self.send_header('Cache-Control', 'no-store')
                    self.send_header('X-Content-Type-Options', 'nosniff')
                    self.end_headers()
                    shutil.copyfileobj(stream, self.wfile)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except OSError:
                self.send_error(404)

        def log_message(self, *_):
            pass
    return Handler


def record_native(output, duration=10, serial=None, adb=None):
    if duration <= 0:
        raise ValueError('Duration must be greater than zero.')
    adb = discover_adb(adb)
    selected = select_device(AdbClient(adb).devices(), serial)
    output = Path(output)
    # Reserve the destination before recording, so existing captures are never replaced.
    with output.open('xb') as destination:
        remote = f'/data/misc/perfetto-traces/device-monitor-{uuid.uuid4().hex}.pftrace'
        prefix = [adb, '-s', selected.serial]
        command = prefix + ['shell', 'perfetto', '-t', f'{duration}s',
                            '-b', '32mb', '-o', remote, 'sched', 'freq', 'idle',
                            'am', 'wm', 'gfx', 'view', 'binder_driver', 'hal',
                            'dalvik', 'camera', 'input']
        try:
            result = subprocess.run(command, capture_output=True,
                                    timeout=duration + 30, check=False)
            if result.returncode:
                raise RuntimeError('Perfetto capture failed: ' +
                                   (result.stdout + result.stderr).decode(errors='replace'))
            # Pull the file separately: exec-out merges diagnostic stderr into the trace.
            with tempfile.TemporaryDirectory(prefix='device-monitor-') as directory:
                captured = Path(directory) / 'capture.pftrace'
                result = subprocess.run(prefix + ['pull', remote, str(captured)],
                                        capture_output=True, timeout=60, check=False)
                if result.returncode or not captured.is_file() or not captured.stat().st_size:
                    raise RuntimeError('Trace transfer failed: ' +
                                       (result.stdout + result.stderr).decode(errors='replace'))
                with captured.open('rb') as source:
                    shutil.copyfileobj(source, destination)
        except BaseException:
            destination.close()
            output.unlink(missing_ok=True)
            raise
        finally:
            try:
                cleanup = subprocess.run(prefix + ['shell', 'rm', '-f', remote],
                                         capture_output=True, timeout=10, check=False)
                if cleanup.returncode:
                    print(f'Could not remove temporary device trace: {remote}', file=sys.stderr)
            except (OSError, subprocess.TimeoutExpired):
                print(f'Could not remove temporary device trace: {remote}', file=sys.stderr)

    print(f'Recorded {output.stat().st_size:,} bytes to {output}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    setup = sub.add_parser('install', help='Download the official UI release (internet required once)')
    setup.add_argument('--version', default=VERSION)
    setup.add_argument('--cache', type=Path, default=CACHE)
    serve = sub.add_parser('serve', help='Serve the installed UI on localhost')
    serve.add_argument('--version', default=VERSION)
    serve.add_argument('--cache', type=Path, default=CACHE)
    serve.add_argument('--port', type=int, default=10000)
    serve.add_argument('--trace', type=Path, help='Open this local trace in Perfetto')
    record = sub.add_parser('record', help='Capture a native Android Perfetto trace over ADB')
    record.add_argument('--output', type=Path, required=True)
    record.add_argument('--duration', type=int, default=10)
    record.add_argument('--serial')
    record.add_argument('--adb')
    args = parser.parse_args()
    try:
        if args.command == 'install':
            install(args.version, args.cache)
        elif args.command == 'record':
            record_native(args.output, args.duration, args.serial, args.adb)
        else:
            root = args.cache / args.version
            if not (root / '.ready').is_file():
                raise ValueError('Run perfetto_local.py install first.')
            if args.trace and not args.trace.is_file():
                raise ValueError(f'Trace does not exist: {args.trace}')
            server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(root, args.trace))
            address = f'http://127.0.0.1:{server.server_port}'
            link = address + ('/#!/?url=' + quote(address + '/trace', safe='') if args.trace else '/')
            print(f'Perfetto UI: {link}', flush=True)
            try:
                server.serve_forever()
            finally:
                server.server_close()
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, RuntimeError, MonitorError, subprocess.TimeoutExpired) as exc:
        print(f'perfetto-local: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
