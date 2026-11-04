"""Select MR checks from the pinned Git trees, using trusted routing rules."""
import json
import os
from pathlib import Path
import subprocess

from candidate import CANDIDATE, revision

APP = ('be', 'pos', 'expo', 'android')
CD = 'infra/cd-stages/'
CD_FILES = {CD + name for name in ('README.md', 'client.py', 'host.py', 'protocol.py', 'test_stages.py')}
PILOT = b"if (env.JOB_NAME != 'yamu/cd-stages-verification') error('"
GUARD = b"if (!(env.JOB_NAME in ['yamu/cd-stages-verification', 'yamu/cd-verification'])) {\n                        error('"


def guard_only(before, after):
    """Recognize the precise reviewed allowlist expansion, including its error block."""
    old = PILOT + '격리 CD 전용 검증 Job에서만 실행할 수 있습니다.'.encode() + b"')"
    new = GUARD + '격리 CD 전용 검증 Job에서만 실행할 수 있습니다.'.encode() + b"')\n                    }"
    return before.count(old) == 1 and before.replace(old, new, 1) == after


def select(paths, guard_change=False):
    if not paths:
        return APP
    checks = set()
    for path in paths:
        if path in CD_FILES or (path == CD + 'Jenkinsfile' and guard_change):
            checks.add('cd')
        else:
            for prefix, check in (('be/', 'be'), ('fe/pos/', 'pos'), ('fe/mobile/', 'expo'), ('fe/android/', 'android')):
                if path.startswith(prefix):
                    checks.add(check)
                    break
            else:
                checks.update(APP)
                if path.startswith(CD):
                    checks.add('cd')
    return tuple(check for check in (*APP, 'cd') if check in checks)


def git(*args):
    return subprocess.check_output(['git', *args], cwd=CANDIDATE, stderr=subprocess.PIPE)


def plan():
    head = revision()
    base = None
    paths = []
    try:
        target = git('rev-parse', 'refs/remotes/origin/develop^{commit}').decode().strip()
        bases = git('merge-base', '--all', target, head).decode().split()
        if len(bases) != 1:
            raise ValueError('Ambiguous merge base')
        base = bases[0]
        paths = [p.decode('utf-8') for p in git('diff', '--no-renames', '--name-only', '-z', base, head, '--').split(b'\0') if p]
        path = CD + 'Jenkinsfile'
        guard_change = path in paths and guard_only(git('show', base + ':' + path), git('show', head + ':' + path))
        checks = select(paths, guard_change)
    except (subprocess.CalledProcessError, ValueError):
        checks = (*APP, 'cd') if any(p.startswith(CD) for p in paths) else APP
    Path('gitlab-status/scope.json').write_text(json.dumps({
        'sha': head, 'merge_base': base, 'paths': paths, 'checks': checks,
        'skipped': [c for c in (*APP, 'cd') if c not in checks],
    }), encoding='utf-8')
    print(','.join(checks))


if __name__ == '__main__':
    plan()
