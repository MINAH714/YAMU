"""Create a manifest only after all CI gates have passed."""
import hashlib
import json
from pathlib import Path
import re
import subprocess


def create(root):
    revision = (root / 'ci-revision.txt').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid revision')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if actual != revision:
        raise ValueError('Checkout revision changed')
    jars = [p for p in (root / 'be/build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
    if len(jars) != 1:
        raise ValueError('Exactly one boot JAR is required')
    jar = jars[0]
    return {'revision': revision, 'jar': jar.relative_to(root).as_posix(),
            'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}


if __name__ == '__main__':
    root = Path.cwd()
    (root / 'ci-manifest.json').write_text(json.dumps(create(root), indent=2) + '\n')
