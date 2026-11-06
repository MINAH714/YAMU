"""Plan checks for the trusted develop job from its last successful revision."""
import json
import os
from pathlib import Path
import re
import subprocess


CHECKS = ('be', 'pos', 'expo')
PARTIAL = {'fe/pos/': 'pos', 'fe/mobile/': 'expo', 'docs/': None}
SHA = re.compile(r'[0-9a-f]{40}')


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def changes(base, head):
    if not SHA.fullmatch(base) or base == head:
        raise ValueError('No distinct successful revision')
    git('merge-base', '--is-ancestor', base, head)
    output = git('diff', '--no-renames', '--name-only', '-z', base, head, '--')
    return [path.decode('utf-8') for path in output.split(b'\0') if path]


def select(paths):
    selected = set()
    for path in paths:
        for prefix, check in PARTIAL.items():
            if path.startswith(prefix):
                selected.add(check)
                break
        else:
            return 'full', CHECKS
    return 'partial', tuple(check for check in CHECKS if check in selected)


def plan(job, branch, force_full, base, head):
    paths = []
    mode, checks = 'full', CHECKS
    if force_full:
        reason = 'requested-full'
    elif (job, branch) != ('yamu/ci-scope-verification-307', 'origin/test/infra/S15P31E103-307-ci-runtime'):
        reason = 'outside-develop-ci'
    else:
        try:
            paths = changes(base, head)
        except (subprocess.CalledProcessError, ValueError):
            reason = 'no-comparable-success'
        else:
            if paths:
                mode, checks = select(paths)
                reason = 'known-paths' if mode == 'partial' else 'full-required-path'
            else:
                reason = 'empty-diff'
    return dict(sha=head, previous_success=base if SHA.fullmatch(base) else None,
                mode=mode, reason=reason, paths=paths, checks=checks,
                skipped=tuple(check for check in CHECKS if check not in checks))


if __name__ == '__main__':
    head = git('rev-parse', 'HEAD').decode().strip()
    scope = plan(os.environ.get('JOB_NAME'), os.environ.get('CI_CHECKOUT_BRANCH'),
                 os.environ.get('CI_FORCE_FULL') == 'true',
                 os.environ.get('CI_PREVIOUS_SUCCESS', ''), head)
    Path('ci-scope.json').write_text(json.dumps(scope, indent=2) + '\n', encoding='utf-8')
    print(','.join((scope['mode'], *scope['checks'])))
