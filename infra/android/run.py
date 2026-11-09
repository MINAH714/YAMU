"""Run Android tooling on the existing Rootless CI engine, without its Docker socket mount."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
AGENT_ROOT = PurePosixPath('/home/jenkins/agent')


def image_tag(directory):
    content = (directory / 'Dockerfile').read_bytes() + (directory / 'requirements.txt').read_bytes()
    return 'yamu-android:toolchain-' + hashlib.sha256(content).hexdigest()[:16]


def agent_volume(inspected):
    matches = [m for m in inspected['Mounts'] if m['Destination'] == str(AGENT_ROOT)]
    if len(matches) != 1 or matches[0]['Type'] != 'volume' or not re.fullmatch(r'[A-Za-z0-9_.-]+', matches[0].get('Name', '')):
        raise ValueError('Expected the named CI work volume, never host bind mounts')
    return matches[0]['Name']


def container_command(action, volume, image, workspace, environment, name):
    workspace = PurePosixPath(workspace)
    if not workspace.is_relative_to(AGENT_ROOT / 'workspace') or '..' in workspace.parts:
        raise ValueError('Workspace must be below the CI agent work volume')
    if action not in ('build', 'publish'):
        raise ValueError('Unsupported container action')
    cmd = ['docker', 'run', '--rm', '--name', name, '--cpus', '2', '--memory', '4g',
           '--label', 'com.yamu.purpose=mock-apk-ci',
           '--mount', 'type=volume,source=' + volume + ',target=' + str(AGENT_ROOT),
           '--workdir', str(workspace), '--env', 'TZ=UTC']
    for key in ('GIT_COMMIT', 'JOB_NAME', 'BUILD_NUMBER'):
        value = environment[key]
        if '\n' in value or '\x00' in value:
            raise ValueError('Invalid build identity')
        cmd += ['--env', key + '=' + value]
    if action == 'build':
        cmd += ['--mount', 'type=volume,source=yamu-apk-gradle-cache,target=/opt/gradle-cache',
                '--mount', 'type=volume,source=yamu-apk-debug-signing,target=/root/.android']
    else:
        credential = PurePosixPath(environment['YAMU_APK_AWS_FILE'])
        if not credential.is_relative_to(PurePosixPath(str(workspace) + '@tmp') / 'secretFiles') or '..' in credential.parts:
            raise ValueError('Only a Jenkins secret-file binding in this workspace is accepted')
        # Only a path, never credential content, appears in the process arguments.
        cmd += ['--env', 'AWS_SHARED_CREDENTIALS_FILE=' + str(credential),
                '--env', 'AWS_EC2_METADATA_DISABLED=true']
    return cmd + [image, 'python3', 'infra/android/' + action + '.py']


def main():
    action = sys.argv[1]
    workspace = Path(os.environ['WORKSPACE']).resolve()
    if workspace != ROOT:
        raise ValueError('Script must belong to the Jenkins checkout')
    security = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .SecurityOptions}}']))
    if 'name=rootless' not in security:
        raise ValueError('Only the Rootless CI engine is allowed')
    tag = image_tag(ROOT / 'infra/android')
    if action == 'image':
        subprocess.run(['docker', 'build', '--pull=false', '-t', tag, '-f', 'infra/android/Dockerfile', 'infra/android'], cwd=ROOT, check=True)
        return
    inspected = json.loads(subprocess.check_output(['docker', 'inspect', os.environ['HOSTNAME']]))[0]
    volume = agent_volume(inspected)
    name = 'yamu-apk-' + uuid.uuid4().hex
    command = container_command(action, volume, tag, workspace, os.environ, name)
    def interrupted(number, frame):
        raise SystemExit(128 + number)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        subprocess.run(command, check=True)
    finally:
        # This invocation's random test/build container only; no project-level pruning.
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'android_step_ok': False, 'error_type': type(error).__name__, 'details_suppressed': True}))
        raise SystemExit(1)
