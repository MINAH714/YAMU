"""Opt-in Docker recovery harness; no production ports, files or database.

Run on Linux: python3 infra/cd/test_compose_recovery.py
Uses an already-present redis:7-alpine image and disposable project resources.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import uuid
from deploy import switch_backend


def main():
    project = 'yamu-cd-test-' + uuid.uuid4().hex[:12]
    subprocess.run(['docker', 'image', 'inspect', 'redis:7-alpine'],
                   check=True, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        path = Path(directory) / 'compose.json'
        config = {'services': {
            'store': {'image': 'redis:7-alpine', 'command': ['redis-server', '--appendonly', 'yes'],
                      'volumes': ['data:/data'],
                      'healthcheck': {'test': ['CMD', 'redis-cli', 'ping'],
                                      'interval': '1s', 'timeout': '1s', 'retries': 10}},
            'backend': {'image': 'redis:7-alpine',
                        'healthcheck': {'test': ['CMD', 'redis-cli', 'ping'],
                                        'interval': '1s', 'timeout': '1s', 'retries': 3}}},
                  'volumes': {'data': {}}}
        path.write_text(json.dumps(config))
        base = ['docker', 'compose', '-p', project, '-f', str(path)]

        def compose(*args):
            return subprocess.check_output(base + list(args), stderr=subprocess.PIPE)

        def apply(broken):
            config['services']['backend']['command'] = ['sh', '-c', 'exit 42'] if broken else ['redis-server']
            path.write_text(json.dumps(config))
            compose('up', '-d', '--no-deps', '--pull', 'never', '--wait',
                    '--wait-timeout', '15', 'backend')

        def verify():
            if compose('exec', '-T', 'backend', 'redis-cli', 'ping').strip() != b'PONG':
                raise RuntimeError('Restored service not healthy')

        try:
            compose('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '20')
            compose('exec', '-T', 'store', 'redis-cli', 'SET', 'recovery-marker', project)
            store_id = compose('ps', '-q', 'store').strip()
            persisted = []
            switch_backend(lambda: apply(False), verify, lambda: persisted.append('ok'), lambda: apply(False))
            if persisted != ['ok']:
                raise AssertionError('Successful deployment was not persisted')
            try:
                switch_backend(lambda: apply(True), verify,
                               lambda: persisted.append('incorrect'), lambda: (apply(False), verify()))
            except subprocess.CalledProcessError:
                pass
            else:
                raise AssertionError('Broken deployment unexpectedly succeeded')
            if persisted != ['ok'] or compose('ps', '-q', 'store').strip() != store_id:
                raise AssertionError('Failure changed persistent service or active state')
            if compose('exec', '-T', 'store', 'redis-cli', 'GET', 'recovery-marker').decode().strip() != project:
                raise AssertionError('Persistent data lost')
            print('PASS: healthy deploy, failed startup, restored health, unchanged store and data')
        finally:
            # Only the randomly named disposable project created above.
            compose('down', '--volumes', '--remove-orphans')


if __name__ == '__main__':
    main()
