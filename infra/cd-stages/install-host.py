#!/usr/bin/env python3
"""Operator-only installation from reviewed root-owned staging, never from CI checkout."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path('/opt/yamu/cd-verification-20261030')
ENTRY = Path('/usr/local/sbin/yamu-cd-verify')
PREFIX = """# S15P31E103-273: fixed progress-protocol dispatch; legacy source follows unchanged.
import os as _os, re as _re
if _re.fullmatch(r'stages:[1-9][0-9]{0,8}:(?:cached|ci):(?:normal|fail-health):[0-9a-f]{32}', _os.environ.get('SSH_ORIGINAL_COMMAND','')):
    _os.execv('/usr/bin/python3', ['python3','/opt/yamu/cd-verification-20261030/stages/host.py'])
"""


def trusted(path):
    st = path.lstat()
    if path.is_symlink() or st.st_uid != 0 or st.st_mode & 0o022:
        raise ValueError('Root-owned non-writable installation source required')


def main():
    if os.geteuid() != 0: raise ValueError('Operator root installation required')
    source = Path(__file__).resolve().parent
    trusted(source)
    for name in ['host-baseline.json', 'host.py', 'protocol.py']: trusted(source / name)
    expected = json.loads((source / 'host-baseline.json').read_text())
    with open(ROOT / 'request.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for name in ['verify.py', 'prepare-candidate.py']:
            trusted(ROOT / name)
            if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected[name]:
                raise ValueError('Installed helper version differs; review required')
        trusted(ENTRY)
        backup = ENTRY.with_name('yamu-cd-verify.before-stages-273')
        original = backup.read_bytes() if backup.exists() else ENTRY.read_bytes()
        if backup.exists(): trusted(backup)
        if hashlib.sha256(original).hexdigest() != expected['entry.py']: raise ValueError('Legacy version differs')
        text = original.decode(); head, body = text.split('\n', 1)
        updated = head + '\n' + PREFIX + body
        previous_protocol = head + '\n' + PREFIX.replace(':(?:cached|ci):', ':') + body
        if ENTRY.read_bytes() not in [original, updated.encode(), previous_protocol.encode()]:
            raise ValueError('Entry modified outside this installation')
        destination = ROOT / 'stages'
        destination.mkdir(mode=0o755, exist_ok=True); trusted(destination)
        for name in ['host.py', 'protocol.py']:
            p = destination / name
            if p.exists(): trusted(p)
            temporary = destination / (name + '.pending')
            with open(temporary, 'x') as stream: stream.write((source / name).read_text())
            temporary.chmod(0o644)
            subprocess.run(['python3', '-m', 'py_compile', str(temporary)], check=True, capture_output=True)
            temporary.replace(p)
        if not backup.exists():
            with open(backup, 'xb') as stream: stream.write(original)
            backup.chmod(0o600)
        temporary = ENTRY.with_name('yamu-cd-verify.stages-pending')
        with open(temporary, 'x') as stream: stream.write(updated)
        temporary.chmod(0o755)
        subprocess.run(['python3', '-m', 'py_compile', str(temporary)], check=True, capture_output=True)
        temporary.replace(ENTRY)
    print('Isolated progress dispatch installed; legacy source and existing access preserved.')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print('Installation stopped: ' + type(error).__name__)
        raise SystemExit(1)
