import unittest
from unittest.mock import patch

import scope


class ScopeTests(unittest.TestCase):
    def test_known_paths_select_union(self):
        self.assertEqual(scope.select(['be/src/Test.java', 'fe/mobile/app.tsx']), ('be', 'expo'))
        self.assertEqual(scope.select(['fe/pos/app.tsx', 'fe/android/app/build.gradle']), ('pos', 'android'))

    def test_empty_shared_and_unknown_paths_run_full_suite(self):
        for paths in ([], ['Jenkinsfile'], ['infra/gitlab/scope.py'], ['fe/shared/config.json'], ['other/unknown']):
            with self.subTest(paths=paths):
                self.assertEqual(scope.select(paths), scope.APP)

    def test_cd_only_and_mixed_changes(self):
        path = 'infra/cd-stages/host.py'
        self.assertEqual(scope.select([path]), ('cd',))
        self.assertEqual(scope.select([path, 'be/build.gradle']), ('be', 'cd'))
        self.assertEqual(scope.select([path, 'infra/compose.dev.yml']), (*scope.APP, 'cd'))

    def test_guard_change_requires_exact_delta(self):
        error = '격리 CD 전용 검증 Job에서만 실행할 수 있습니다.'.encode()
        before = b'prefix\n' + scope.PILOT + error + b"')\nsuffix"
        after = b'prefix\n' + scope.GUARD + error + b"')\n                    }\nsuffix"
        self.assertTrue(scope.guard_only(before, after))
        self.assertFalse(scope.guard_only(before, after + b'\nextra()'))
        path = 'infra/cd-stages/Jenkinsfile'
        self.assertEqual(scope.select([path], True), ('cd',))
        self.assertEqual(scope.select([path]), (*scope.APP, 'cd'))

    def test_ambiguous_merge_base_falls_back_to_app_suite(self):
        with patch.object(scope, 'revision', return_value='a' * 40), \
                patch.object(scope, 'git', side_effect=[b'target', b'base1 base2']), \
                patch.object(scope.Path, 'write_text'), patch('builtins.print') as output:
            scope.plan()
        output.assert_called_once_with('be,pos,expo,android')
