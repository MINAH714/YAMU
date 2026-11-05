"""Validate an exact MR checkout using only trusted control scripts; no publishing credentials."""
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = ROOT / 'candidate'


def sibling(directory, name):
    spec = importlib.util.spec_from_file_location('trusted_' + name, ROOT / directory / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def revision():
    expected = os.environ['YAMU_PINNED_SHA']
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=CANDIDATE, text=True).strip()
    if not re.fullmatch('[0-9a-f]{40}', expected) or actual != expected:
        raise ValueError('Candidate does not match pinned MR SHA')
    return expected


def android():
    revision()
    docker = sibling('infra/android', 'run')
    security = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .SecurityOptions}}']))
    if 'name=rootless' not in security: raise ValueError('Only the Rootless CI engine is supported')
    image = docker.image_tag(ROOT / 'infra/android')
    subprocess.run(['docker', 'build', '--pull=false', '-t', image, '-f', str(ROOT / 'infra/android/Dockerfile'),
                    str(ROOT / 'infra/android')], check=True)
    obj = json.loads(subprocess.check_output(['docker', 'inspect', os.environ['HOSTNAME']]))[0]
    volume = docker.agent_volume(obj)
    name = 'yamu-gitlab-android-' + uuid.uuid4().hex
    cmd = ['docker', 'run', '--rm', '--name', name, '--cpus', '2', '--memory', '4g',
           '--label', 'com.yamu.purpose=mr-ci',
           '--mount', 'type=volume,source=' + volume + ',target=/home/jenkins/agent',
           '--mount', 'type=volume,source=yamu-mr-android-gradle-cache,target=/opt/gradle-cache',
           '--workdir', str(ROOT), '--env', 'GIT_COMMIT=' + revision(),
           '--env', 'YAMU_PINNED_SHA=' + revision(), '--env', 'TZ=UTC',
           '--env', 'JOB_NAME=' + os.environ['JOB_NAME'], '--env', 'BUILD_NUMBER=' + os.environ['BUILD_NUMBER'],
           image, 'python3', str(Path(__file__).resolve()), 'android-inner']
    def interrupted(number, frame): raise SystemExit(128 + number)
    signal.signal(signal.SIGTERM, interrupted)
    try: subprocess.run(cmd, check=True)
    finally: subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    action = sys.argv[1]
    revision()
    if action == 'check':
        print('Pinned candidate revision verified.')
    elif action == 'android':
        android()
    elif action == 'android-inner':
        # Reuse the reviewed mock APK gates, never the candidate's build/publish Python code.
        build = sibling('infra/android', 'build')
        build.ROOT = CANDIDATE
        build.APP = CANDIDATE / 'fe/android'
        build.OUT = CANDIDATE / 'apk-out'
        build.main()
    else: raise ValueError('Unknown candidate action')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'candidate_ok': False, 'error_type': type(error).__name__}))
        raise SystemExit(1)
