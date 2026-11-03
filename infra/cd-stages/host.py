#!/usr/bin/env python3
"""Root-owned isolated CD runner. Never execute a caller-supplied path or command."""
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from protocol import request

ROOT = Path('/opt/yamu/cd-verification-20261030')
PROJECT = 'yamu-cd-verify-20261029'


class Events:
    def __init__(self, run_id, journal, output):
        self.run_id, self.journal, self.output, self.seq = run_id, journal, output, 0

    def send(self, kind, **values):
        self.seq += 1
        value = {'kind': kind, 'run_id': self.run_id, 'seq': self.seq, 'time': time.time(), **values}
        line = json.dumps(value) + '\n'
        self.journal.write(line); self.journal.flush(); os.fsync(self.journal.fileno())
        if self.output is not None:
            try: self.output.write(line); self.output.flush()
            except (BrokenPipeError, OSError): self.output = None

    @contextmanager
    def phase(self, name):
        self.send('phase', phase=name, state='running')
        try: yield
        except BaseException:
            self.send('phase', phase=name, state='failed'); raise
        else: self.send('phase', phase=name, state='success')


def execute(adapter, events, scenario):
    """One lock is owned by the caller across every phase and compensation."""
    touched = False
    try:
        with events.phase('validate'): adapter.validate()
        with events.phase('prepare'): adapter.prepare()
        touched = True
        with events.phase('baseline'): adapter.baseline()
        with events.phase('deploy'): adapter.deploy()
        with events.phase('health'):
            adapter.health_candidate()
            if scenario == 'fail-health': raise ValueError('Injected isolated health failure')
        with events.phase('persist'): adapter.persist_candidate()
        with events.phase('manual_rollback'): adapter.manual_rollback()
        with events.phase('automatic_rollback'): adapter.automatic_rollback()
        with events.phase('persistence'): adapter.persistence()
        with events.phase('final_deploy'): adapter.final_deploy()
        with events.phase('final_health'): report = adapter.final_health()
        events.send('done', state='success', result=report)
        return 0
    except BaseException as error:
        recovered = False
        if touched:
            try:
                try: events.send('phase', phase='recovery', state='running')
                except Exception: pass
                adapter.recover()
                recovered = True
            except BaseException: pass
            try: events.send('phase', phase='recovery', state='success' if recovered else 'failed')
            except Exception: pass
        try: events.send('done', state='failed', error_type=type(error).__name__, recovery_healthy=recovered)
        except Exception: pass
        return 1


def trusted_module(name):
    path = ROOT / (name + '.py')
    st = path.lstat()
    if path.is_symlink() or st.st_uid != 0 or st.st_mode & 0o022: raise ValueError('Untrusted installed helper')
    spec = importlib.util.spec_from_file_location('installed_' + name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


class Adapter:
    def __init__(self, build, source):
        self.build, self.source = build, source
        self.verify = trusted_module('verify')
        self.preparer = trusted_module('prepare-candidate')

    def validate(self):
        v = self.verify
        self.meta = json.loads((ROOT / 'metadata.json').read_text())
        self.cfg = json.loads((ROOT / 'compose.json').read_text())
        if self.meta['project'] != PROJECT or self.meta['rds_used'] is not False: raise ValueError('Wrong verification project')
        if v.PROJECT != PROJECT or v.ROOT != ROOT: raise ValueError('Wrong installed helper target')
        url = self.cfg['services']['backend']['environment']['SPRING_DATASOURCE_URL']
        if not url.startswith('jdbc:mysql://db:3306/yamu_cd_verify?'): raise ValueError('External DB forbidden')
        if self.cfg['services']['backend'].get('ports'): raise ValueError('Public backend port forbidden')
        if v.production() != self.meta['production_baseline']: raise ValueError('Production baseline differs')
        for s in ['backend', 'db', 'redis']: v.service(s)
        self.initial = v.service('backend')['Image']
        self.health(self.initial)
        if self.source == 'cached':
            cached = self.meta['artifacts']['candidate']
            if self.meta['candidate_build'] != self.build or cached['ci_build'] != self.build:
                raise ValueError('Cached fixture build differs')
            data = (ROOT / 'candidate/app.jar').read_bytes()
            if hashlib.sha256(data).hexdigest() != cached['jar_sha256']: raise ValueError('Cached fixture digest differs')
            if self.preparer.migrations(data) != self.preparer.migrations((ROOT / 'baseline/app.jar').read_bytes()):
                raise ValueError('Cached fixture schema differs')
            self.revision = cached['revision']
            if not re.fullmatch('[0-9a-f]{40}', self.revision): raise ValueError('Invalid cached revision')
            image = json.loads(v.run(['docker', 'image', 'inspect', cached['image']]))[0]
            if image['Id'] != cached['image'] or image['Config'].get('Labels', {}).get('yamu.verification.revision') != self.revision:
                raise ValueError('Cached image provenance differs')
            return
        archive = '/var/jenkins_home/jobs/yamu/jobs/ci/builds/' + str(self.build) + '/'
        def read(path): return subprocess.check_output(['docker', 'exec', 'yamu-ci-jenkins-1', 'cat', archive + path], stderr=subprocess.PIPE)
        if ET.fromstring(read('build.xml')).findtext('result') != 'SUCCESS': raise ValueError('CI must be completed SUCCESS')
        self.manifest = json.loads(read('archive/ci-manifest.json'))
        self.revision = self.manifest['revision']
        if not re.fullmatch('[0-9a-f]{40}', self.revision): raise ValueError('Invalid revision')

    def prepare(self):
        if self.source == 'ci':
            subprocess.run(['python3', str(ROOT / 'prepare-candidate.py'), str(self.build), self.revision],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.meta = json.loads((ROOT / 'metadata.json').read_text())
        self.old = self.meta['artifacts']['baseline']['image']
        self.candidate = self.meta['artifacts']['candidate']['image']
        if any(not re.fullmatch('sha256:[0-9a-f]{64}', i) for i in [self.old, self.candidate, self.initial]):
            raise ValueError('Invalid local image identity')

    def health(self, image):
        v = self.verify; obj = v.service('backend')
        if obj['Image'] != image or obj['State'].get('Health', {}).get('Status') != 'healthy': raise ValueError('Backend unhealthy')
        for path in ['/actuator/health', '/api/health']:
            data = json.loads(v.run(['docker', 'exec', PROJECT + '-backend-1', 'curl', '--fail', '--silent',
                                    '--max-time', '10', 'http://127.0.0.1:8080' + path]))
            if path == '/actuator/health' and data.get('status') != 'UP': raise ValueError('Actuator unhealthy')
            if path == '/api/health' and data.get('status') != 'ok' and data.get('data', {}).get('status') != 'ok':
                raise ValueError('API unhealthy')
        if v.db('SELECT 1') != '1' or v.redis('PING') != 'PONG': raise ValueError('Dependency unhealthy')

    def apply(self, image, broken=False):
        backend = {'image': image, 'healthcheck': self.cfg['services']['backend']['healthcheck']}
        if broken: backend['healthcheck'] = {'test': ['CMD', 'false'], 'interval': '2s', 'timeout': '2s', 'retries': 2, 'start_period': '0s'}
        self.verify.write_json('stage-attempt.json', {'services': {'backend': backend}})
        self.verify.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait',
                            '--wait-timeout', '30' if broken else '120', 'backend', override='stage-attempt.json')

    def persist(self, image):
        self.verify.write_json('active.json', {'services': {'backend': {'image': image}}})
        self.cfg['services']['backend']['image'] = image
        self.verify.write_json('compose.json', self.cfg)

    def baseline(self):
        v = self.verify
        self.db_id = v.service('db')['Id']; self.redis_id = v.service('redis')['Id']
        self.mounts = {s: [(m.get('Name'), m['Destination']) for m in v.service(s)['Mounts']] for s in ['db', 'redis']}
        v.db("CREATE TABLE IF NOT EXISTS cd_verification_probe (id INT PRIMARY KEY, marker VARCHAR(100) NOT NULL); "
             "INSERT INTO cd_verification_probe VALUES (1,'yamu-cd-verify-20261029') ON DUPLICATE KEY UPDATE marker=VALUES(marker);")
        v.redis('SET', 'cd-verification-marker', PROJECT)
        # Make the previous and candidate versions distinct for a meaningful recovery test.
        self.apply(self.old); self.health(self.old); self.persist(self.old)
        self.initial = self.old

    def deploy(self): self.apply(self.candidate)
    def health_candidate(self): self.health(self.candidate)
    def persist_candidate(self): self.persist(self.candidate)
    def manual_rollback(self): self.apply(self.old); self.health(self.old); self.persist(self.old)

    def automatic_rollback(self):
        before = (ROOT / 'active.json').read_bytes()
        rejected = False
        try: self.apply(self.candidate, broken=True)
        except subprocess.CalledProcessError:
            rejected = self.verify.service('backend')['State'].get('Health', {}).get('Status') == 'unhealthy'
        if not rejected: raise ValueError('Expected unhealthy fixture was not rejected')
        self.apply(self.old); self.health(self.old)
        if (ROOT / 'active.json').read_bytes() != before: raise ValueError('Failed candidate was persisted')

    def persistence(self):
        v = self.verify
        if v.service('db')['Id'] != self.db_id or v.service('redis')['Id'] != self.redis_id: raise ValueError('Dependency container changed')
        mounts = {s: [(m.get('Name'), m['Destination']) for m in v.service(s)['Mounts']] for s in ['db', 'redis']}
        if mounts != self.mounts: raise ValueError('Dependency volume changed')
        if v.db('SELECT marker FROM cd_verification_probe WHERE id=1') != PROJECT or v.redis('GET', 'cd-verification-marker') != PROJECT:
            raise ValueError('Persistent marker lost')

    def final_deploy(self): self.apply(self.candidate); self.health(self.candidate); self.persist(self.candidate)

    def final_health(self):
        self.health(self.candidate); self.persistence()
        if self.verify.production() != self.meta['production_baseline']: raise ValueError('Production baseline changed')
        return {'ci_build': self.build, 'ci_revision': self.revision, 'project': PROJECT, 'rds_used': False,
                'artifact_source': 'cached_fixture' if self.source == 'cached' else 'retained_ci',
                'successful_deploy': True, 'manual_rollback': True, 'automatic_rollback_healthy': True,
                'db_marker_preserved': True, 'redis_marker_preserved': True, 'final_candidate_healthy': True,
                'production_unchanged': True}

    def recover(self):
        self.apply(self.initial); self.health(self.initial); self.persist(self.initial)


def main():
    import fcntl
    if os.geteuid() != 0: raise ValueError('Privileged installed entry point required')
    os.umask(0o077)
    build, source, scenario, run_id = request(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
    # Disconnect does not interrupt compensation. No new caller may bypass these same locks.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    with open(ROOT / 'request.lock', 'a') as request_lock, \
            open('/home/ubuntu/cd-verification-20261029/test.lock', 'a') as legacy_lock, \
            open(ROOT / 'test.lock', 'a') as test_lock:
        for lock in [request_lock, legacy_lock, test_lock]: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        directory = ROOT / 'stage-requests'; directory.mkdir(mode=0o700, exist_ok=True)
        # Reusing a request identity is rejected, even after completion; never replay stale success.
        with open(directory / (run_id + '.jsonl'), 'x') as journal:
            events = Events(run_id, journal, sys.stdout)
            def expired(number, frame):
                signal.alarm(0)
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
                signal.signal(signal.SIGINT, signal.SIG_IGN)
                raise TimeoutError('Isolated verification deadline')
            signal.signal(signal.SIGTERM, expired); signal.signal(signal.SIGINT, expired)
            signal.signal(signal.SIGALRM, expired); signal.alarm(12 * 60)
            try: return execute(Adapter(build, source), events, scenario)
            finally: signal.alarm(0)


if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as error:
        print('Isolated verification stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
