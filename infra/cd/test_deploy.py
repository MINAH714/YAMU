import hashlib
import io
import unittest
import zipfile
from deploy import migrations, validate, switch_backend, release_override, protected_environment
import copy


def jar(sql):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as file:
        file.writestr('BOOT-INF/classes/db/migration/V1__init.sql', sql)
    return data.getvalue()


class DeploymentGates(unittest.TestCase):
    def test_protected_values_are_compared_without_unrelated_runtime_variables(self):
        before = {'Config': {'Env': ['SSAFY_API_KEY=fake=a', 'YAMU_CRYPTO_TEST=fake', 'PATH=/bin']}}
        after = {'Config': {'Env': ['SSAFY_API_KEY=fake=a', 'YAMU_CRYPTO_TEST=fake', 'PATH=/new']}}
        self.assertEqual(protected_environment(before), protected_environment(after))
        after['Config']['Env'].append('YAMU_POS_KEY=unexpected')
        self.assertNotEqual(protected_environment(before), protected_environment(after))

    def test_release_and_rollback_preserve_api_key_sources_and_ai(self):
        active = {'services': {
            'backend': {'image': 'old', 'env_file': ['/private/backend.env'],
                        'environment': {'AI_BASE_URL': 'http://ai:8001'},
                        'networks': ['default', 'database', 'ai_internal']},
            'ai': {'image': 'ai:unchanged', 'networks': ['ai_internal'], 'read_only': True}},
            'networks': {'ai_internal': {'internal': True}}}
        original = copy.deepcopy(active)
        current = []
        persisted = []
        def start(image):
            current.append(release_override(active, image))
        def failure():
            raise RuntimeError('Health failed')
        with self.assertRaises(RuntimeError):
            switch_backend(lambda: start('candidate'), failure,
                           lambda: persisted.append(True), lambda: start('old'))
        self.assertEqual(current[-1], original)
        self.assertFalse(persisted)
        self.assertEqual(active, original)
        candidate = current[0]
        self.assertEqual(candidate['services']['backend'].pop('image'), 'candidate')
        expected = copy.deepcopy(original)
        del expected['services']['backend']['image']
        self.assertEqual(candidate, expected)

    def test_invalid_active_state_stops_instead_of_dropping_configuration(self):
        for state in (None, [], {}, {'services': []}, {'services': {'backend': []}}):
            with self.subTest(state=state), self.assertRaises(ValueError):
                release_override(state, 'new')

    def test_failures_restore_previous_image_and_never_claim_success(self):
        for failed in ('apply', 'verify', 'persist'):
            calls = []
            def step(name):
                def call():
                    calls.append(name)
                    if name == failed:
                        raise RuntimeError(name)
                return call
            with self.subTest(failed=failed), self.assertRaises(RuntimeError):
                switch_backend(step('apply'), step('verify'), step('persist'), step('rollback'))
            self.assertEqual(calls[-1], 'rollback')

    def test_success_persists_only_after_health(self):
        calls = []
        switch_backend(*(lambda name=name: calls.append(name)
                         for name in ('apply', 'verify', 'persist', 'rollback')))
        self.assertEqual(calls, ['apply', 'verify', 'persist'])

    def test_digest_and_revision(self):
        data = jar('CREATE TABLE a (id INT);')
        manifest = {'revision': 'a' * 40, 'jar': 'be/build/libs/app.jar',
                    'sha256': hashlib.sha256(data).hexdigest()}
        validate(manifest, data, 'a' * 40)
        for mutation in ({'revision': 'b' * 40}, {'sha256': '0' * 64},
                         {'jar': '../../secret'}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate(manifest | mutation, data, 'a' * 40)

    def test_changed_migration_cannot_use_image_rollback(self):
        self.assertNotEqual(migrations(jar('CREATE TABLE a (id INT);')),
                            migrations(jar('DROP TABLE a;')))


if __name__ == '__main__':
    unittest.main()
