import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import hashlib
import unittest
from unittest.mock import patch

import client
import host
from protocol import PHASES, RESULT_FLAGS, accept, event, request

RUN = 'a' * 32


def result():
    return {'project': host.PROJECT, 'rds_used': False, 'ci_build': 21, 'ci_revision': 'b' * 40, 'artifact_source': 'cached_fixture',
            **dict.fromkeys(RESULT_FLAGS, True)}


class Fake:
    def __init__(self, failure=None): self.actions = []; self.failure = failure; self.restored = False
    def __getattr__(self, name):
        def action():
            self.actions.append(name)
            if self.failure == name: raise ValueError('private test fixture must not enter event data')
            if name == 'recover': self.restored = True
            if name == 'final_health': return result()
        return action


class BrokenOutput:
    def write(self, value): raise BrokenPipeError('fixture')


class HostFlow(unittest.TestCase):
    def run_flow(self, adapter=None, scenario='normal', output=None):
        adapter = adapter or Fake()
        with tempfile.TemporaryFile(mode='w+', encoding='utf-8') as journal:
            events = host.Events(RUN, journal, output or io.StringIO())
            code = host.execute(adapter, events, scenario)
            journal.seek(0); lines = journal.readlines()
        snapshot = {'phases': {}, 'done': None}
        for seq, line in enumerate(lines, 1): accept(snapshot, event(line, RUN, seq))
        return code, adapter, snapshot, lines

    def test_normal_executes_all_real_actions_before_terminal_success(self):
        code, adapter, snapshot, lines = self.run_flow()
        self.assertEqual(code, 0)
        self.assertEqual(snapshot['done']['state'], 'success')
        self.assertEqual(list(snapshot['phases']), PHASES)
        self.assertNotIn('recover', adapter.actions)
        self.assertEqual(adapter.actions[-1], 'final_health')

    def test_injected_health_failure_restores_previous_image_and_remains_failed(self):
        code, adapter, snapshot, lines = self.run_flow(scenario='fail-health')
        self.assertEqual(code, 1); self.assertTrue(adapter.restored)
        self.assertEqual(snapshot['phases']['health'], 'failed')
        self.assertEqual(snapshot['phases']['recovery'], 'success')
        self.assertNotIn('persist_candidate', adapter.actions)
        self.assertEqual(snapshot['done']['state'], 'failed')

    def test_every_mutating_phase_failure_attempts_recovery(self):
        for name in ['deploy', 'health_candidate', 'persist_candidate', 'manual_rollback',
                     'automatic_rollback', 'persistence', 'final_deploy', 'final_health']:
            code, adapter, snapshot, lines = self.run_flow(Fake(name))
            self.assertEqual(code, 1); self.assertTrue(adapter.restored, name)
            self.assertNotIn('private test fixture', ''.join(lines))

    def test_validation_or_preparation_failure_does_not_touch_backend(self):
        for name in ['validate', 'prepare', 'baseline']:
            code, adapter, snapshot, lines = self.run_flow(Fake(name))
            self.assertEqual(code, 1); self.assertFalse(adapter.restored)
            self.assertNotIn('deploy', adapter.actions)

    def test_failed_compensation_is_never_reported_healthy(self):
        code, adapter, snapshot, lines = self.run_flow(Fake('recover'), 'fail-health')
        self.assertEqual(snapshot['phases']['recovery'], 'failed')
        self.assertFalse(snapshot['done']['recovery_healthy'])

    def test_disconnect_does_not_stop_host_or_release_locks_early(self):
        code, adapter, snapshot, lines = self.run_flow(output=BrokenOutput())
        self.assertEqual(code, 0); self.assertEqual(snapshot['done']['state'], 'success')
        code, adapter, snapshot, lines = self.run_flow(scenario='fail-health', output=BrokenOutput())
        self.assertTrue(adapter.restored); self.assertEqual(code, 1)

    def test_deadline_exception_during_deploy_still_compensates(self):
        adapter = Fake()
        adapter.deploy = lambda: (_ for _ in ()).throw(TimeoutError('fixture'))
        code, adapter, snapshot, lines = self.run_flow(adapter)
        self.assertTrue(adapter.restored); self.assertEqual(code, 1)

    def test_progress_disk_error_does_not_prevent_compensation(self):
        adapter = Fake()
        class FailingEvents:
            phase = host.Events.phase
            def send(self, kind, **values):
                if values.get('phase') in ['health', 'recovery']: raise OSError('private disk fixture')
        code = host.execute(adapter, FailingEvents(), 'normal')
        self.assertEqual(code, 1); self.assertTrue(adapter.restored)


class Protocol(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'Linux host flock contract')
    def test_same_lock_rejects_another_execution_without_waiting(self):
        import fcntl
        with tempfile.TemporaryDirectory() as tmp:
            with open(Path(tmp) / 'request.lock', 'a') as first, open(Path(tmp) / 'request.lock', 'a') as second:
                fcntl.flock(first, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with self.assertRaises(BlockingIOError): fcntl.flock(second, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_request_is_only_fixed_build_scenario_and_run_id(self):
        self.assertEqual(request('stages:21:cached:normal:' + RUN), (21, 'cached', 'normal', RUN))
        for value in ['verify:21', 'stages:0:cached:normal:' + RUN, 'stages:21:prod:normal:' + RUN,
                      'stages:21:cached:normal:../../tmp', 'stages:21:cached:normal:' + RUN + ';id',
                      'stages:21:cached:normal:' + RUN + '\n']:
            with self.assertRaises(ValueError): request(value)

    def test_stale_duplicate_out_of_order_or_oversized_events_are_rejected(self):
        value = {'kind': 'phase', 'run_id': RUN, 'seq': 1, 'phase': 'validate', 'state': 'running'}
        for change in [{'run_id': 'c' * 32}, {'seq': 2}, {'phase': 'production'}, {'state': 'complete'}]:
            with self.assertRaises(ValueError): event(json.dumps({**value, **change}), RUN, 1)
        with self.assertRaises(ValueError): event('x' * 16385, RUN, 1)
        snap = {'phases': {}, 'done': None}
        accept(snap, value)
        with self.assertRaises(ValueError): accept(snap, value)
        with self.assertRaises(ValueError): accept({'phases': {}, 'done': None}, {**value, 'phase': 'deploy'})

    def test_terminal_success_requires_all_phases_and_evidence(self):
        done = {'kind': 'done', 'run_id': RUN, 'seq': 1, 'state': 'success', 'result': result()}
        with self.assertRaises(ValueError): accept({'phases': {}, 'done': None}, done)
        for r in [{}, {**result(), 'rds_used': True}, {**result(), 'final_candidate_healthy': False}]:
            with self.assertRaises(ValueError): event(json.dumps({**done, 'result': r}), RUN, 1)


class AgentProgress(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self.tmp.name) / 'stage-status'
        self.patch = patch.object(client, 'DIRECTORY', self.directory); self.patch.start()
        with patch.dict(os.environ, {'YAMU_CD_BUILD': '21', 'YAMU_CD_SCENARIO': 'normal'}): client.initialize()

    def tearDown(self): self.patch.stop(); self.tmp.cleanup()

    def test_reused_agent_directory_is_rejected_before_transport(self):
        with patch.dict(os.environ, {'YAMU_CD_BUILD': '21', 'YAMU_CD_SCENARIO': 'normal'}):
            with self.assertRaises(FileExistsError): client.initialize()

    def test_eof_without_final_result_is_failure_not_success(self):
        snapshot = client.load()
        with self.assertRaises(ValueError): client.consume([], snapshot)
        self.assertEqual(client.poll('final', 'finished'), 10)

    def test_phase_failure_remains_red_while_recovery_is_green(self):
        snapshot = client.load();snapshot['phases'] = {'health': 'failed', 'recovery': 'success'}
        snapshot['done'] = {'state': 'failed'};client.save(snapshot)
        self.assertEqual(client.poll('health', 'finished'), 20)
        self.assertEqual(client.poll('recovery', 'finished'), 0)
        self.assertEqual(client.poll('final', 'finished'), 20)

    def test_missing_phase_after_terminal_failure_does_not_wait_forever(self):
        snapshot = client.load();snapshot['done'] = {'state': 'failed'};client.save(snapshot)
        self.assertEqual(client.poll('deploy', 'started'), 20)


class CachedFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        for n in ['baseline', 'candidate']: (self.root / n).mkdir()
        for n in ['baseline/app.jar', 'candidate/app.jar']: (self.root / n).write_bytes(b'fixture jar')
        self.image = 'sha256:' + 'a' * 64; self.revision = 'b' * 40
        meta = {'project': host.PROJECT, 'rds_used': False, 'production_baseline': {}, 'candidate_build': 21,
                'artifacts': {'candidate': {'ci_build': 21, 'revision': self.revision, 'image': self.image,
                    'jar_sha256': hashlib.sha256(b'fixture jar').hexdigest()}}}
        (self.root / 'metadata.json').write_text(json.dumps(meta))
        (self.root / 'compose.json').write_text(json.dumps({'services': {'backend': {'environment': {
            'SPRING_DATASOURCE_URL': 'jdbc:mysql://db:3306/yamu_cd_verify?fixture=true'}}}}))
        self.image_record = {'Id': self.image, 'Config': {'Labels': {'yamu.verification.revision': self.revision}}}
        def run(args):
            if 'inspect' in args: return json.dumps([self.image_record]).encode()
            return json.dumps({'status': 'UP' if args[-1].endswith('/actuator/health') else 'ok'}).encode()
        self.verify = SimpleNamespace(ROOT=self.root, PROJECT=host.PROJECT, production=lambda: {},
            service=lambda name: {'Image': self.image, 'State': {'Health': {'Status': 'healthy'}}},
            db=lambda sql: '1', redis=lambda *args: 'PONG', run=run)
        self.preparer = SimpleNamespace(migrations=lambda value: {'migration': value})

    def tearDown(self): self.tmp.cleanup()

    def validate(self):
        with patch.object(host, 'ROOT', self.root), patch.object(host, 'trusted_module',
                side_effect=lambda name: self.verify if name == 'verify' else self.preparer), \
                patch.object(host.subprocess, 'check_output', side_effect=AssertionError('Cached mode must not claim retained CI')):
            host.Adapter(21, 'cached').validate()

    def test_cached_mode_explicitly_verifies_pinned_cache_without_claiming_live_ci(self): self.validate()

    def test_modified_cached_jar_is_rejected(self):
        (self.root / 'candidate/app.jar').write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.validate()

    def test_cached_migration_difference_is_rejected(self):
        (self.root / 'baseline/app.jar').write_bytes(b'other schema')
        with self.assertRaises(ValueError): self.validate()

    def test_image_revision_must_match_cached_artifact(self):
        self.image_record['Config']['Labels']['yamu.verification.revision'] = 'c' * 40
        with self.assertRaises(ValueError): self.validate()


if __name__ == '__main__': unittest.main()
