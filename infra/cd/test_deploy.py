import hashlib
import io
import unittest
import zipfile
from deploy import migrations, validate, switch_backend


def jar(sql):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as file:
        file.writestr('BOOT-INF/classes/db/migration/V1__init.sql', sql)
    return data.getvalue()


class DeploymentGates(unittest.TestCase):
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
