"""Opt-in Docker recovery harness; no production ports, files or database.

Run on Linux: python3 infra/cd/test_compose_recovery.py
Uses an already-present redis:7-alpine image and disposable project resources.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import uuid
from deploy import switch_backend, release_override


def main():
    project = 'yamu-cd-test-' + uuid.uuid4().hex[:12]
    subprocess.run(['docker', 'image', 'inspect', 'redis:7-alpine'],
                   check=True, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        path = Path(directory) / 'compose.json'
        key_file = Path(directory) / 'synthetic.env'
        key_file.write_text('YAMU_POS_KEY=fake-pos\nYAMU_ADMIN_KEY=fake-admin\nSSAFY_API_KEY=fake-api\n')
        key_file.chmod(0o600)
        config = {'services': {
            'store': {'image': 'redis:7-alpine', 'command': ['redis-server', '--appendonly', 'yes'],
                      'volumes': ['data:/data'],
                      'healthcheck': {'test': ['CMD', 'redis-cli', 'ping'],
                                      'interval': '1s', 'timeout': '1s', 'retries': 10}},
            'backend': {'image': 'redis:7-alpine',
                        'env_file': [str(key_file)],
                        'environment': {'AI_BASE_URL': 'http://ai:8001'},
                        'networks': ['default', 'ai_internal'],
                        'healthcheck': {'test': ['CMD', 'redis-cli', 'ping'],
                                        'interval': '1s', 'timeout': '1s', 'retries': 3}},
            'ai': {'image': 'redis:7-alpine', 'networks': ['ai_internal']}},
                  'volumes': {'data': {}}, 'networks': {'ai_internal': {'internal': True}}}
        path.write_text(json.dumps(config))
        active = {'services': {'backend': config['services']['backend'],
                               'ai': config['services']['ai']},
                  'networks': config['networks']}
        override = Path(directory) / 'active.json'
        override.write_text(json.dumps(active))
        base = ['docker', 'compose', '-p', project, '-f', str(path), '-f', str(override)]

        def compose(*args):
            return subprocess.check_output(base + list(args), stderr=subprocess.PIPE)

        def apply(broken):
            candidate = release_override(active, 'redis:7-alpine')
            candidate['services']['backend']['command'] = ['sh', '-c', 'exit 42'] if broken else ['redis-server']
            override.write_text(json.dumps(candidate))
            compose('up', '-d', '--no-deps', '--pull', 'never', '--wait',
                    '--wait-timeout', '15', 'backend')

        def verify():
            if compose('exec', '-T', 'backend', 'redis-cli', 'ping').strip() != b'PONG':
                raise RuntimeError('Restored service not healthy')
            compose('exec', '-T', 'backend', 'sh', '-c',
                    'test "$YAMU_POS_KEY" = fake-pos && test "$YAMU_ADMIN_KEY" = fake-admin '
                    '&& test "$SSAFY_API_KEY" = fake-api && test "$AI_BASE_URL" = http://ai:8001')
            if compose('exec', '-T', 'backend', 'redis-cli', '-h', 'ai', 'ping').strip() != b'PONG':
                raise RuntimeError('Internal AI connection lost')

        try:
            compose('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '20')
            compose('exec', '-T', 'store', 'redis-cli', 'SET', 'recovery-marker', project)
            store_id = compose('ps', '-q', 'store').strip()
            ai_id = compose('ps', '-q', 'ai').strip()
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
            if compose('ps', '-q', 'ai').strip() != ai_id:
                raise AssertionError('Existing AI was recreated')
            if compose('exec', '-T', 'store', 'redis-cli', 'GET', 'recovery-marker').decode().strip() != project:
                raise AssertionError('Persistent data lost')
            print('PASS: deploy, failed startup, rollback; key sources and AI network retained; AI/store unchanged')
        finally:
            # Only the randomly named disposable project created above.
            compose('down', '--volumes', '--remove-orphans')


if __name__ == '__main__':
    main()
