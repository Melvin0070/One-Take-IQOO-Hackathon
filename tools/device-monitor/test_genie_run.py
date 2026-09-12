import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch
import genie_run
from genie_run import prepare_config


class GenieConfigTests(unittest.TestCase):
    def test_rejects_cpu_and_mixed_backend_runs(self):
        for engines in [[], [{'backend': {'type': 'QnnCpu'}}],
                        [{'backend': {'type': 'QnnHtp'}}, {'backend': {'type': 'QnnCpu'}}]]:
            with self.subTest(engines=engines), self.assertRaisesRegex(ValueError, 'QnnHtp'):
                prepare_config({'dialog': {'engine': engines}}, 64)

    def test_caps_response_without_modifying_source_configuration(self):
        config = {'dialog': {'engine': {'backend': {'type': 'QnnHtp'}},
                             'context': {'size': 4096}, 'max-num-tokens': 1000}}
        prepared = prepare_config(config, 32)
        self.assertEqual(prepared['dialog']['max-num-tokens'], 32)
        self.assertEqual(prepared['dialog']['context']['size'], 4096)
        self.assertEqual(config['dialog']['max-num-tokens'], 1000)

    def test_rejects_malformed_config_shapes(self):
        for config in [[], None, {'dialog': []}, {'dialog': {'engine': [1]}},
                       {'dialog': {'engine': {'backend': []}}}]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                prepare_config(config, 32)

    def test_failed_workspace_creation_records_failure_and_attempts_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bundle = root / 'bundle'
            bundle.mkdir()
            (bundle / 'genie_config.json').write_text(json.dumps(
                {'dialog': {'engine': {'backend': {'type': 'QnnHtp'}}}}))
            sdk = root / 'sdk'
            paths = ['bin/aarch64-android/genie-app',
                     'lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so']
            paths += ['lib/aarch64-android/' + name for name in [
                'libGenie.so', 'libQnnHtp.so', 'libQnnHtpPrepare.so',
                'libQnnHtpV81Stub.so', 'libQnnSystem.so', 'libQnnHtpNetRunExtensions.so']]
            for name in paths:
                path = sdk / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            calls = []

            def run(command, **kwargs):
                calls.append(command)
                if 'getprop' in command[-1]:
                    return SimpleNamespace(returncode=0, stdout=b'SM8850', stderr=b'')
                return SimpleNamespace(returncode=1, stdout=b'', stderr=b'disconnected')

            with patch.object(genie_run, 'discover_adb', return_value='adb'), \
                    patch.object(genie_run, 'AdbClient'), \
                    patch.object(genie_run, 'select_device', return_value=SimpleNamespace(serial='phone')), \
                    patch.object(genie_run.subprocess, 'run', side_effect=run):
                with self.assertRaisesRegex(RuntimeError, 'workspace creation failed'):
                    genie_run.run_bundle(sdk, bundle, 'genie_config.json', root / 'output', 'hello')
            record = json.loads((root / 'output/run.json').read_text())
            self.assertEqual(record['status'], 'failed')
            self.assertFalse(record['remote_cleanup_succeeded'])
            self.assertIn('disconnected', record['error'])
            self.assertIn('rm -rf /data/local/tmp/device-monitor-genie-', calls[-1][-1])
            self.assertNotEqual(record['source_config_sha256'], record['executed_config_sha256'])

    def test_rejects_unbounded_generation(self):
        for limit in [0, -1, 513]:
            with self.subTest(limit=limit), self.assertRaises(ValueError):
                prepare_config({}, limit)


if __name__ == '__main__':
    unittest.main()
