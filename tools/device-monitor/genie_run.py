#!/usr/bin/env python3
"""Run a prepared QNN HTP Genie bundle locally on SM8850 and retain profiling evidence."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

from device_monitor import AdbClient, MonitorError, discover_adb, select_device


def prepare_config(config, max_tokens):
    if not 1 <= max_tokens <= 512:
        raise ValueError('max-tokens must be between 1 and 512')
    if not isinstance(config, dict) or not isinstance(config.get('dialog'), dict):
        raise ValueError('Config must contain a dialog object')
    result = copy.deepcopy(config)
    dialog = result['dialog']
    engines = dialog.get('engine', [])
    if isinstance(engines, dict):
        engines = [engines]
    if (not isinstance(engines, list) or not engines
            or any(not isinstance(e, dict) or not isinstance(e.get('backend'), dict)
                   or e['backend'].get('type') != 'QnnHtp' for e in engines)):
        raise ValueError('This runner requires an explicit QnnHtp backend for every engine')
    dialog['max-num-tokens'] = max_tokens
    return result


def run_bundle(sdk, bundle, config_name, output, prompt, max_tokens=64, serial=None, adb=None):
    sdk, bundle, output = Path(sdk).resolve(), Path(bundle).resolve(), Path(output).resolve()
    config_path = (bundle / config_name).resolve()
    if config_path.parent != bundle:
        raise ValueError('Config must be a file directly inside the bundle directory')
    config = prepare_config(json.loads(config_path.read_text()), max_tokens)
    binaries = [sdk / 'bin/aarch64-android/genie-app']
    libraries = ['libGenie.so', 'libQnnHtp.so', 'libQnnHtpPrepare.so',
                 'libQnnHtpV81Stub.so', 'libQnnSystem.so', 'libQnnHtpNetRunExtensions.so']
    binaries += [sdk / 'lib/aarch64-android' / name for name in libraries]
    binaries += [sdk / 'lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so']
    for path in binaries:
        if not path.is_file():
            raise ValueError(f'Required SDK file missing: {path}')
    adb = discover_adb(adb)
    selected = select_device(AdbClient(adb).devices(), serial)
    prefix = [adb, '-s', selected.serial]

    def command(args, timeout=120):
        return subprocess.run(prefix + args, capture_output=True, timeout=timeout, check=False)

    def shell(args, timeout=120):
        return command(['shell', shlex.join(args)], timeout)

    chipset = shell(['getprop', 'ro.soc.model']).stdout.decode().strip()
    if chipset != 'SM8850':
        raise ValueError(f'This validated runner targets SM8850/v81; connected chipset is {chipset}')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'run-config.json').write_text(json.dumps(config, indent=2))
    (output / 'prompt.txt').write_text(prompt)
    (output / 'profile-config.json').write_text(json.dumps(
        {'profile': {'version': 1, 'trace': {'version': 1, 'enable': True}}}))
    (output / 'run-script.txt').write_text('\n'.join([
        'profile config create pc monitor-profile-config.json',
        'profile create profiler pc',
        'dialog config create dc monitor-config.json',
        'dialog config bind profile dc profiler',
        'dialog create dialog dc',
        'dialog query dialog monitor-prompt.txt monitor-response.txt',
        'dialog free dialog',
        'profile save profiler monitor-profile.json',
        'dialog config free dc',
        'profile free profiler',
        'profile config free pc',
        '',
    ]))
    remote = '/data/local/tmp/device-monitor-genie-' + uuid.uuid4().hex
    metadata = {'device_serial': selected.serial, 'chipset': chipset, 'sdk_version': sdk.name,
                'requested_backend': 'QnnHtp', 'model_bundle': str(bundle),
                'source_config_sha256': hashlib.sha256(config_path.read_bytes()).hexdigest(),
                'executed_config_sha256': hashlib.sha256((output / 'run-config.json').read_bytes()).hexdigest(),
                'max_tokens': max_tokens, 'remote_workspace': remote,
                'npu_utilization': {'status': 'unsupported', 'value': None},
                'status': 'failed'}
    try:
        result = shell(['mkdir', remote])
        if result.returncode:
            raise RuntimeError('Remote workspace creation failed: ' + result.stderr.decode(errors='replace'))
        for source, target in [(bundle, remote + '/model'), *[(p, remote + '/' + p.name) for p in binaries],
                               (output / 'run-config.json', remote + '/model/monitor-config.json'),
                               (output / 'prompt.txt', remote + '/model/monitor-prompt.txt'),
                               (output / 'profile-config.json', remote + '/model/monitor-profile-config.json'),
                               (output / 'run-script.txt', remote + '/model/monitor-run-script.txt')]:
            result = command(['push', str(source), target], timeout=300)
            if result.returncode:
                raise RuntimeError('ADB transfer failed: ' + result.stderr.decode(errors='replace'))
        shell(['chmod', '700', remote + '/genie-app'])
        # Device-side timeout prevents inference continuing after an ADB client timeout.
        invocation = ['timeout', '120', 'env', f'LD_LIBRARY_PATH={remote}',
                      f'ADSP_LIBRARY_PATH={remote}', remote + '/genie-app',
                      '-s', 'monitor-run-script.txt']
        remote_command = 'cd ' + shlex.quote(remote + '/model') + ' && ' + shlex.join(invocation)
        print('Running QnnHtp inference...', flush=True)
        start = time.monotonic()
        result = command(['shell', remote_command], timeout=135)
        metadata['host_elapsed_s'] = time.monotonic() - start
        metadata['returncode'] = result.returncode
        (output / 'stdout.txt').write_bytes(result.stdout)
        (output / 'stderr.txt').write_bytes(result.stderr)
        pulled = command(['pull', remote + '/model/monitor-profile.json', str(output / 'profile.json')])
        response_pull = command(['pull', remote + '/model/monitor-response.txt', str(output / 'response.txt')])
        if result.returncode:
            raise RuntimeError(f'Genie exited {result.returncode}; see {output}/stdout.txt and stderr.txt')
        if pulled.returncode or not (output / 'profile.json').stat().st_size:
            raise RuntimeError('Inference returned successfully but no profiling evidence was produced')
        if response_pull.returncode or not (output / 'response.txt').stat().st_size:
            raise RuntimeError('Inference produced no text response')
        profile = json.loads((output / 'profile.json').read_text())
        if not profile.get('traceEvents'):
            raise RuntimeError('Genie profile is missing trace events')
        queries = [e for c in profile.get('components', []) for e in c.get('events', [])
                   if e.get('type') == 'GenieDialog_query']
        if not queries:
            raise RuntimeError('Genie profile contains no completed dialog query')
        metadata['query_events'] = queries
        metadata['trace_event_count'] = len(profile['traceEvents'])
        metadata['status'] = 'completed'
        metadata['execution_evidence'] = 'Successful Genie run with every engine explicitly configured QnnHtp; inspect raw profile for device timings.'
        print(result.stdout.decode(errors='replace'), flush=True)
    except BaseException as exc:
        metadata['error'] = str(exc)
        raise
    finally:
        try:
            cleanup = shell(['rm', '-rf', remote], timeout=15)
            metadata['remote_cleanup_succeeded'] = cleanup.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            metadata['remote_cleanup_succeeded'] = False
        (output / 'run.json').write_text(json.dumps(metadata, indent=2))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--config', default='genie_config.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--prompt-file', type=Path, required=True,
                        help='A model-formatted prompt; content stays on the local host and phone')
    parser.add_argument('--max-tokens', type=int, default=64)
    parser.add_argument('--serial')
    parser.add_argument('--adb')
    args = parser.parse_args()
    try:
        run_bundle(args.sdk, args.bundle, args.config, args.output, args.prompt_file.read_text(),
                   args.max_tokens, args.serial, args.adb)
        return 0
    except (ValueError, OSError, RuntimeError, MonitorError, subprocess.TimeoutExpired) as exc:
        print(f'genie-run: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
