import hashlib
import base64
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import urlopen

import perfetto_local


class LocalPerfettoTests(unittest.TestCase):
    def test_manifest_paths_cannot_escape_cache(self):
        for path in ['../secret', '/secret', 'assets/../../secret', 'https://other.test/x', 'a\\b']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                perfetto_local.resource_path(Path('/cache'), path)
        self.assertEqual(perfetto_local.resource_path(Path('/cache'), 'assets/font.woff2'),
                         Path('/cache/assets/font.woff2'))

    def test_failed_reinstall_invalidates_existing_ready_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / perfetto_local.VERSION
            root.mkdir()
            (root / '.ready').write_text(perfetto_local.VERSION)
            with patch.object(perfetto_local.shutil, 'which', return_value='/usr/bin/curl'), \
                    patch.object(perfetto_local, 'download', side_effect=RuntimeError('download failed')):
                with self.assertRaisesRegex(RuntimeError, 'download failed'):
                    perfetto_local.install(cache=Path(directory))
            self.assertFalse((root / '.ready').exists())

    def test_hash_mismatch_is_rejected(self):
        digest = 'sha256-' + base64.b64encode(hashlib.sha256(b'expected').digest()).decode()
        with self.assertRaisesRegex(ValueError, 'checksum'):
            perfetto_local.verify_resource(b'changed', digest)
        perfetto_local.verify_resource(b'expected', digest)

    def test_capture_pulls_binary_separately_and_preserves_existing_file(self):
        payload = b'\x0a\x01\x00'
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            if 'pull' in command:
                Path(command[-1]).write_bytes(payload)
            return SimpleNamespace(returncode=0, stdout=b'diagnostic text', stderr=b'')

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'trace.pftrace'
            with patch.object(perfetto_local, 'discover_adb', return_value='adb'), \
                    patch.object(perfetto_local, 'AdbClient'), \
                    patch.object(perfetto_local, 'select_device', return_value=SimpleNamespace(serial='phone')), \
                    patch.object(perfetto_local.subprocess, 'run', side_effect=run):
                perfetto_local.record_native(output, duration=1)
                self.assertEqual(output.read_bytes(), payload)
                self.assertEqual(commands[0][3:5], ['shell', 'perfetto'])
                remote = commands[0][commands[0].index('-o') + 1]
                self.assertIn(remote, commands[1])
                self.assertEqual(commands[2][-3:], ['rm', '-f', remote])
                with self.assertRaises(FileExistsError):
                    perfetto_local.record_native(output, duration=1)
                self.assertEqual(len(commands), 3)
                self.assertEqual(output.read_bytes(), payload)

    def test_failed_capture_removes_empty_output_and_cleans_remote_file(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'trace.pftrace'
            with patch.object(perfetto_local, 'discover_adb', return_value='adb'), \
                    patch.object(perfetto_local, 'AdbClient'), \
                    patch.object(perfetto_local, 'select_device', return_value=SimpleNamespace(serial='phone')), \
                    patch.object(perfetto_local.subprocess, 'run', side_effect=[
                        SimpleNamespace(returncode=1, stdout=b'', stderr=b'capture denied'),
                        SimpleNamespace(returncode=0, stdout=b'', stderr=b'')]) as run:
                with self.assertRaisesRegex(RuntimeError, 'capture denied'):
                    perfetto_local.record_native(output, duration=1)
                self.assertFalse(output.exists())
                self.assertEqual(run.call_count, 2)
                self.assertEqual(run.call_args.args[0][-3:-1], ['rm', '-f'])

    def test_local_server_serves_selected_trace_and_only_static_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'ui'
            root.mkdir()
            (root / 'index.html').write_text('<title>Perfetto UI</title>')
            (root / 'trace_processor.wasm').write_bytes(b'wasm-data')
            trace = Path(directory) / 'selected.pftrace'
            trace.write_bytes(b'\x0a\x01\x00')
            (Path(directory) / 'private.txt').write_text('private')
            server = ThreadingHTTPServer(('127.0.0.1', 0), perfetto_local.handler_for(root, trace))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                url = f'http://127.0.0.1:{server.server_port}'
                with urlopen(url + '/') as response:
                    self.assertEqual(response.read(), b'<title>Perfetto UI</title>')
                with urlopen(url + '/trace') as response:
                    self.assertEqual(response.read(), b'\x0a\x01\x00')
                with urlopen(url + '/trace_processor.wasm') as response:
                    self.assertEqual(response.headers.get_content_type(), 'application/wasm')
                for path in ['/../private.txt', '/%2e%2e/private.txt', '/folder/']:
                    with self.assertRaises(HTTPError) as error:
                        urlopen(url + path)
                    self.assertEqual(error.exception.code, 404)
            finally:
                server.shutdown()
                server.server_close()


if __name__ == '__main__':
    unittest.main()
