#!/usr/bin/env python3
"""Trusted host-side deployment entry point; never run this from CI checkout.

Install root-owned, with root-owned config and forced SSH command. The caller
supplies only a ci-master build number. No caller path, image or shell is accepted.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

CONFIG = Path('/etc/yamu-cd/config.json')


def run(args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.PIPE, **kwargs)


def migrations(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        return {name: hashlib.sha256(jar.read(name)).hexdigest()
                for name in jar.namelist() if name.startswith('BOOT-INF/classes/db/migration/')
                and not name.endswith('/')}


def validate(manifest, jar, revision):
    if manifest.get('revision') != revision or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Candidate is not current master')
    if manifest.get('sha256') != hashlib.sha256(jar).hexdigest():
        raise ValueError('Artifact digest mismatch')
    if not re.fullmatch(r'be/build/libs/[A-Za-z0-9._-]+\.jar', manifest.get('jar', '')):
        raise ValueError('Invalid artifact path')


def switch_backend(apply, verify, persist, rollback):
    try:
        apply()
        verify()
        persist()
    except BaseException:
        rollback()
        raise


def main():
    import fcntl  # Host entry point is Linux; pure validation tests also run on Windows.
    build = os.environ.get('SSH_ORIGINAL_COMMAND', '')
    if not re.fullmatch(r'[1-9][0-9]{0,8}', build):
        raise ValueError('Only a build number is accepted')
    if os.geteuid() != 0:
        raise ValueError('Use the installed privileged entry point')
    lock = open('/run/lock/yamu-cd.lock', 'w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    cfg = json.loads(CONFIG.read_text())
    controller = cfg['jenkins_container']
    archive = f'/var/jenkins_home/jobs/yamu/jobs/ci-master/builds/{build}'

    def read_artifact(path):
        return run(['docker', 'exec', controller, 'cat', archive + '/' + path])

    result = ET.fromstring(read_artifact('build.xml'))
    if result.findtext('result') != 'SUCCESS' or result.find('building') is not None and result.findtext('building') != 'false':
        raise ValueError('CI build is not a completed SUCCESS')
    manifest = json.loads(read_artifact('archive/ci-manifest.json'))
    artifact_path = manifest.get('jar', '')
    if not re.fullmatch(r'be/build/libs/[A-Za-z0-9._-]+\.jar', artifact_path):
        raise ValueError('Invalid artifact path')
    jar = read_artifact('archive/' + artifact_path)

    def master():
        ref = run(['git', '-C', cfg['repository'], 'ls-remote', '--exit-code',
                   'origin', 'refs/heads/master'], text=True).strip().split()
        if len(ref) != 2 or ref[1] != 'refs/heads/master':
            raise ValueError('Cannot resolve master')
        return ref[0]

    revision = master()
    validate(manifest, jar, revision)
    if read_artifact('archive/ci-revision.txt').decode().strip() != revision:
        raise ValueError('Revision record mismatch')
    current = json.loads(run(['docker', 'inspect', 'yamu-prod-backend-1']))[0]
    old_image = current['Image']
    if current['State'].get('Health', {}).get('Status') != 'healthy':
        raise ValueError('Existing backend must be healthy')
    with tempfile.TemporaryDirectory(prefix='yamu-cd-', dir=cfg['staging']) as temp:
        temp = Path(temp)
        run(['docker', 'cp', 'yamu-prod-backend-1:/app/app.jar', str(temp / 'old.jar')])
        if migrations((temp / 'old.jar').read_bytes()) != migrations(jar):
            raise ValueError('Migration changed: use a separately reviewed DB deployment')
        (temp / 'app.jar').write_bytes(jar)
        # BuildKit treats FROM sha256:... as a registry name. Give the exact
        # local image a content-derived tag before using it as the runtime.
        runtime = 'yamu-cd-runtime:' + old_image.removeprefix('sha256:')
        run(['docker', 'tag', old_image, runtime])
        (temp / 'Dockerfile').write_text(
            f'FROM {runtime}\nCOPY --chown=10001:10001 app.jar /app/app.jar\n'
            f'LABEL org.opencontainers.image.revision="{revision}"\n')
        image = 'yamu-backend:cd-' + revision
        run(['docker', 'build', '--pull=false', '--network', 'none', '-t', image, str(temp)])
        new_image = run(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], text=True).strip()
        base = ['docker', 'compose', '--env-file', cfg['prod_env'], '--env-file', cfg['rds_env'],
                '-f', cfg['compose_prod'], '-f', cfg['compose_rds']]

        def compose(target, action):
            override = temp / 'image.json'
            override.write_text(json.dumps({'services': {'backend': {'image': target}}}))
            return run(base + ['-f', str(override)] + action)

        compose(new_image, ['config', '--quiet'])
        if master() != revision:
            raise ValueError('master changed while preparing deployment')
        state = Path(cfg['active_override'])
        pending = state.with_suffix('.pending')
        pending.write_text(json.dumps({'services': {'backend': {'image': new_image}}}) + '\n')
        os.chmod(pending, 0o600)
        def start(target):
            compose(target, ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                             '--wait', '--wait-timeout', '180', 'backend'])

        def verify_health():
            health = json.loads(run(['curl', '--fail', '--silent', '--max-time', '15',
                                     'http://127.0.0.1:8080/actuator/health']))
            api = json.loads(run(['curl', '--fail', '--silent', '--max-time', '15',
                                  'https://yamu.it.kr/api/health']))
            if health.get('status') != 'UP' or api.get('status') != 'ok':
                raise ValueError('Deployment health failed')
        def rollback():
            start(old_image)
            verify_health()
            print('Previous image restored; candidate deployment failed.', flush=True)

        switch_backend(lambda: start(new_image), verify_health,
                       lambda: os.replace(pending, state), rollback)
        # Persist the selected image before claiming completion. A stable override
        # is required for subsequent operator restarts (do not use old APP_VERSION).
        print(json.dumps({'result': 'DEPLOYED', 'build': build, 'revision': revision,
                          'image': new_image, 'previous_image': old_image}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Command output / container environment may contain secrets. Never echo it.
        print('Deployment stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
