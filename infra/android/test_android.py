import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

DIRECTORY = Path(__file__).parent
def module(name):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

build = module('build')
run = module('run')
# publish imports the sibling build module when executed normally.
import sys
sys.modules['build'] = build
publish = module('publish')


class BuildGates(unittest.TestCase):
    def test_real_api_or_pos_key_rejected(self):
        good = 'DEBUG = Boolean.parseBoolean("true"); REAL_API = ""; POS_KEY = "";'
        build.require_mock_config(good)
        for bad in [good.replace('REAL_API = ""', 'REAL_API = "auth"'),
                    good.replace('POS_KEY = ""', 'POS_KEY = "dummy-test-value"'),
                    good.replace('"true"', '"false"'), good.replace('REAL_API', 'OTHER')]:
            with self.assertRaises(ValueError): build.require_mock_config(bad)

    def test_missing_failed_and_skipped_tests_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            with self.assertRaises(ValueError): build.test_totals(directory)
            for attrs in ['tests="0"', 'tests="2" failures="1"', 'tests="2" errors="1"', 'tests="2" skipped="1"']:
                (directory / 'TEST-results.xml').write_text('<testsuite ' + attrs + '/>')
                with self.assertRaises(ValueError): build.test_totals(directory)
            (directory / 'TEST-results.xml').write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase name="one"/><testcase name="two"/></testsuite>')
            self.assertEqual(build.test_totals(directory)['tests'], 2)

    def test_xml_failure_cannot_hide_behind_zero_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / 'TEST-results.xml').write_text('<testsuite tests="1" failures="0"><testcase name="bad"><failure/></testcase></testsuite>')
            with self.assertRaises(ValueError): build.test_totals(directory)


class Isolation(unittest.TestCase):
    def setUp(self):
        self.env = {'GIT_COMMIT': 'a' * 40, 'JOB_NAME': 'yamu/mobile/apk-mock', 'BUILD_NUMBER': '7',
                    'YAMU_APK_AWS_FILE': '/home/jenkins/agent/workspace/apk@tmp/secretFiles/id/aws.ini',
                    'AWS_SECRET_ACCESS_KEY': 'test-only-must-not-propagate'}

    def command(self, action, env=None):
        return run.container_command(action, 'agent_work', 'yamu-android:test', '/home/jenkins/agent/workspace/apk', env or self.env, 'yamu-apk-test')

    def test_build_has_no_credentials_socket_or_publish_mount(self):
        args = self.command('build')
        self.assertFalse(any(s in ' '.join(args) for s in ['test-only-must-not-propagate', 'AWS_', 'secretFiles', 'docker.sock', '--volumes-from']))
        self.assertIn('type=volume,source=yamu-apk-debug-signing,target=/root/.android', args)
        self.assertIn('4g', args)

    def test_publisher_has_no_private_signing_or_gradle_cache(self):
        args = self.command('publish')
        self.assertNotIn('debug-signing', ' '.join(args))
        self.assertNotIn('gradle-cache', ' '.join(args))
        self.assertNotIn('test-only-must-not-propagate', ' '.join(args))

    def test_foreign_credential_path_rejected(self):
        for path in ['/etc/aws.ini', '/home/jenkins/agent/workspace/other@tmp/secretFiles/aws.ini',
                     '/home/jenkins/agent/workspace/apk@tmp/secretFiles/../../other.ini']:
            with self.assertRaises(ValueError): self.command('publish', {**self.env, 'YAMU_APK_AWS_FILE': path})

    def test_host_bind_is_never_used_as_agent_volume(self):
        with self.assertRaises(ValueError): run.agent_volume({'Mounts': [{'Destination': '/home/jenkins/agent', 'Type': 'bind', 'Source': '/'}]})
        self.assertEqual(run.agent_volume({'Mounts': [{'Destination': '/home/jenkins/agent', 'Type': 'volume', 'Name': 'agent_work'}]}), 'agent_work')


class ArtifactContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.commit = 'a' * 40
        self.apk = self.directory / ('YAMU-mock-customer-' + self.commit[:8] + '.apk')
        self.apk.write_bytes(b'fixture-not-a-real-apk')
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.manifest = {'source_revision': self.commit, 'variant': 'customerDebug', 'mock_only': True,
            'real_api_features': [], 'pos_key_present': False, 'signature_verified': True,
            'android_package': 'com.yamu.app', 'min_android_api': 30, 'apk_filename': self.apk.name,
            'bytes': self.apk.stat().st_size, 'sha256': digest,
            'unit_tests': {'tests': 115, 'failures': 0, 'errors': 0, 'skipped': 0}}
        (self.directory / (self.apk.name + '.sha256')).write_text(digest + '  ' + self.apk.name + '\n')
        (self.directory / 'build-manifest.json').write_text(json.dumps(self.manifest))

    def validate(self, manifest=None):
        return publish.validate_manifest(manifest or self.manifest, self.commit, self.directory)

    def test_manifest_accepts_exact_regular_files_only(self):
        self.assertEqual(len(self.validate()), 3)
        for field, value in [('source_revision', 'b' * 40), ('variant', 'customerRelease'), ('real_api_features', ['auth']),
                             ('mock_only', False), ('signature_verified', False), ('apk_filename', '../other.apk')]:
            with self.assertRaises(ValueError): self.validate({**self.manifest, field: value})

    def test_changed_apk_or_sidecar_rejected(self):
        self.apk.write_bytes(b'changed')
        with self.assertRaises(ValueError): self.validate()

    def test_failed_tests_block_publish(self):
        with self.assertRaises(ValueError): self.validate({**self.manifest, 'unit_tests': {'tests': 115, 'failures': 1, 'errors': 0, 'skipped': 0}})

    def test_retry_keeps_matching_object_and_rejects_conflict(self):
        class Conflict(Exception):
            response = {'ResponseMetadata': {'HTTPStatusCode': 412}}
        class Fake:
            class exceptions: ClientError = Conflict
            length = self.apk.stat().st_size
            def put_object(client, **kwargs):
                client.checksum = kwargs['ChecksumSHA256']
                self.assertEqual(kwargs['IfNoneMatch'], '*')
                raise Conflict()
            def head_object(client, **kwargs):
                return {'ChecksumSHA256': client.checksum, 'ContentLength': client.length}
        fake = Fake()
        publish.upload_one(fake, self.apk, 'builds/android/mock/test.apk', self.commit)
        fake.length += 1
        with self.assertRaises(ValueError): publish.upload_one(fake, self.apk, 'builds/android/mock/test.apk', self.commit)


if __name__ == '__main__': unittest.main()
