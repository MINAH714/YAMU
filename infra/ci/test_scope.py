from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import scope


class ScopeTest(unittest.TestCase):
    def test_known_paths(self):
        self.assertEqual(scope.select(['fe/pos/src/app.ts']), ('partial', ('pos',)))
        self.assertEqual(scope.select(['fe/mobile/App.tsx']), ('partial', ('expo',)))
        self.assertEqual(scope.select(['fe/mobile/App.tsx', 'fe/pos/src/app.ts', 'docs/guide.md']),
                         ('partial', ('pos', 'expo')))
        self.assertEqual(scope.select(['docs/guide.md']), ('partial', ()))

    def test_backend_common_and_unknown_paths_keep_all_checks(self):
        for path in ['be/build.gradle', 'Jenkinsfile', 'infra/cd/manifest.py',
                     'ai/Dockerfile', 'fe/android/build.gradle']:
            with self.subTest(path=path):
                self.assertEqual(scope.select(['fe/pos/src/app.ts', path]), ('full', scope.CHECKS))

    def test_release_and_other_jobs_bypass_path_selection(self):
        for job, branch, force, reason in [
            ('yamu/ci-scope-verification-307', 'origin/test/infra/S15P31E103-307-ci-runtime', True, 'requested-full'),
            ('yamu/ci-master', 'origin/master', False, 'outside-develop-ci'),
            ('yamu/ci', 'origin/master', False, 'outside-develop-ci'),
        ]:
            with self.subTest(job=job, branch=branch, force=force), patch('scope.changes') as changes:
                plan = scope.plan(job, branch, force, 'a' * 40, 'b' * 40)
                self.assertEqual((plan['mode'], plan['checks'], plan['reason']), ('full', scope.CHECKS, reason))
                changes.assert_not_called()

    def test_missing_revision_forces_full(self):
        result = scope.plan('yamu/ci-scope-verification-307', 'origin/test/infra/S15P31E103-307-ci-runtime', False, '', 'b' * 40)
        self.assertEqual((result['mode'], result['reason']), ('full', 'no-comparable-success'))


class GitScopeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI scope test')
        self.git('config', 'user.email', 'ci-test@example.invalid')
        self.git('config', 'commit.gpgSign', 'false')
        patcher = patch('scope.git', side_effect=self.git)
        patcher.start()
        self.addCleanup(patcher.stop)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.PIPE)

    def commit(self, path, contents):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents, encoding='utf-8')
        return self.commit_index()

    def commit_index(self):
        self.git('add', '--all')
        self.git('commit', '--quiet', '--allow-empty', '-m', 'fixture')
        return self.git('rev-parse', 'HEAD').decode().strip()

    def plan(self, base, head):
        return scope.plan('yamu/ci-scope-verification-307', 'origin/test/infra/S15P31E103-307-ci-runtime', False, base, head)

    def test_compares_across_failed_or_unbuilt_revisions(self):
        good = self.commit('be/src/Rule.java', 'original')
        failed = self.commit('be/src/Rule.java', 'changed')
        head = self.commit('docs/guide.md', 'docs only in this commit')
        self.assertEqual(self.plan(good, head)['paths'], ['be/src/Rule.java', 'docs/guide.md'])
        self.assertEqual(self.plan(good, head)['mode'], 'full')
        self.assertEqual(self.plan(failed, head)['mode'], 'partial')

    def test_rename_checks_deleted_source_as_well_as_destination(self):
        base = self.commit('be/src/Rule.java', 'original')
        (self.root / 'docs').mkdir()
        self.git('mv', 'be/src/Rule.java', 'docs/moved.txt')
        result = self.plan(base, self.commit_index())
        self.assertEqual(result['paths'], ['be/src/Rule.java', 'docs/moved.txt'])
        self.assertEqual(result['mode'], 'full')

    def test_missing_non_ancestor_and_empty_diff_force_full(self):
        base = self.commit('docs/guide.md', 'base')
        other = self.commit('docs/guide.md', 'other')
        self.git('checkout', '--quiet', '--detach', base)
        empty = self.commit_index()
        for previous, reason in [(base, 'empty-diff'), (other, 'no-comparable-success'),
                                 ('f' * 40, 'no-comparable-success')]:
            with self.subTest(previous=previous):
                result = self.plan(previous, empty)
                self.assertEqual((result['mode'], result['reason']), ('full', reason))


if __name__ == '__main__':
    unittest.main()
