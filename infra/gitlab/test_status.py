import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error

import status

SHA = 'a' * 40
REF = 'feat/be/S15P31E103-123-example'
URL = 'http://100.102.125.41:8081/job/yamu/job/gitlab/job/mr-ci/7/'


class Response(io.BytesIO):
    def __init__(self, obj): super().__init__(json.dumps(obj).encode())


class Opener:
    def __init__(self, answers): self.answers = list(answers); self.requests = []
    def open(self, req, timeout):
        self.requests.append(req)
        answer = self.answers.pop(0)
        if isinstance(answer, Exception): raise answer
        return Response(answer)


def mr(**changes):
    return {'iid': 9, 'sha': SHA, 'state': 'opened', 'source_project_id': 7, 'target_project_id': 7,
            'source_branch': REF, 'target_branch': 'develop', **changes}


class Fake:
    def __init__(self, detail=None, last=None): self.detail = detail or mr(); self.last = last
    def opened(self): return [mr()]
    def mr(self, iid): return self.detail
    def latest(self, revision, branch): return self.last


class Reporting(unittest.TestCase):
    def test_status_is_bound_to_exact_sha_ref_and_job_link(self):
        opener = Opener([{'status': 'running'}])
        client = status.Client('public-unit-test-fixture', opener)
        client.report(SHA, REF, 'running', status.MR_CONTEXT, URL, 'MR validation')
        req = opener.requests[0]
        self.assertEqual(req.full_url, status.API + '/projects/' + status.PROJECT + '/statuses/' + SHA)
        form = __import__('urllib.parse', fromlist=['parse_qs']).parse_qs(req.data.decode())
        self.assertEqual(form['ref'], [REF]); self.assertEqual(form['target_url'], [URL])
        self.assertNotIn('public-unit-test-fixture', req.full_url)
        self.assertNotIn('public-unit-test-fixture', req.data.decode())

    def test_wrong_revision_branch_context_or_link_sends_nothing(self):
        opener = Opener([]); client = status.Client('fixture', opener)
        for revision, branch, context, link in [('develop', REF, status.MR_CONTEXT, URL),
                (SHA, '../other', status.MR_CONTEXT, URL), (SHA, REF, 'unrelated-status', URL),
                (SHA, REF, status.MR_CONTEXT, 'https://example.invalid/steal')]:
            with self.assertRaises(ValueError): client.report(revision, branch, 'success', context, link, '')
        self.assertEqual(opener.requests, [])

    def test_conflict_is_retried_boundedly_but_auth_failure_is_not(self):
        conflict = urllib.error.HTTPError('https://example.invalid', 409, 'fixture', {}, None)
        opener = Opener([conflict, conflict, {'id': 1}]); delays = []
        self.assertEqual(status.Client('fixture', opener, delays.append).request('GET'), {'id': 1})
        self.assertEqual(delays, [1, 2])
        forbidden = urllib.error.HTTPError('https://example.invalid', 401, 'fixture', {}, None)
        opener = Opener([forbidden])
        with self.assertRaises(urllib.error.HTTPError): status.Client('fixture', opener, delays.append).request('GET')
        self.assertEqual(len(opener.requests), 1)

    def test_redirect_cannot_forward_api_token(self):
        with self.assertRaises(ValueError): status.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid')

    def test_status_choice_uses_latest_matching_context(self):
        values = [{'id': 2, 'name': status.MR_CONTEXT, 'ref': REF, 'status': 'failed'},
                  {'id': 1, 'name': status.MR_CONTEXT, 'ref': REF, 'status': 'running'},
                  {'id': 99, 'name': 'other', 'ref': REF, 'status': 'success'}]
        client = status.Client('fixture', Opener([values]))
        self.assertEqual(client.latest(SHA, REF)['status'], 'failed')


class Selection(unittest.TestCase):
    def test_only_same_project_open_develop_mr_is_selected(self):
        self.assertEqual(status.choose(Fake(), 7)['sha'], SHA)
        for change in [{'source_project_id': 8}, {'target_project_id': 8}, {'state': 'merged'}, {'target_branch': 'master'}]:
            self.assertIsNone(status.choose(Fake(mr(**change)), 7))

    def test_existing_status_does_not_automatically_rebuild_same_sha(self):
        for value in ['pending', 'running', 'success', 'failed', 'canceled']:
            self.assertIsNone(status.choose(Fake(last={'status': value}), 7))

    def test_manual_retry_requires_explicit_mr(self):
        with self.assertRaises(ValueError): status.choose(Fake(), 7, retry=True)

    def test_manual_retry_never_duplicates_active_build(self):
        for value in ['pending', 'running']:
            self.assertIsNone(status.choose(Fake(last={'status': value}), 7, iid='9', retry=True))
        self.assertIsNotNone(status.choose(Fake(last={'status': 'failed'}), 7, iid='9', retry=True))

    def test_missing_or_wrong_sha_fails_closed(self):
        with self.assertRaises(ValueError): status.choose(Fake(mr(sha='develop')), 7)
        detail = mr(); detail.pop('sha'); detail['diff_refs'] = {'head_sha': SHA}
        self.assertEqual(status.choose(Fake(detail), 7)['sha'], SHA)

    def test_pinned_context_cannot_be_replaced_after_candidate_execution(self):
        context = status.choose(Fake(), 7)
        status.validate_context(context, SHA, REF, 9)
        for key, value in [('sha', 'b' * 40), ('ref', 'develop'), ('iid', 10), ('project_id', '7')]:
            with self.assertRaises(ValueError): status.validate_context({**context, key: value}, SHA, REF, 9)

    def test_new_head_or_closed_mr_never_receives_old_success(self):
        context = status.choose(Fake(), 7)
        self.assertEqual(status.final_state(context, mr(), 'success'), 'success')
        self.assertEqual(status.final_state(context, mr(sha='b' * 40), 'success'), 'canceled')
        self.assertEqual(status.final_state(context, mr(state='closed'), 'success'), 'canceled')

    def test_invalid_token_file_is_never_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'token'
            p.write_text('unit-test-placeholder-api-token'); p.chmod(0o600)
            self.assertEqual(status.read_token(p), 'unit-test-placeholder-api-token')
            p.write_text('unit.test-fixture+/with=punctuation')
            self.assertEqual(status.read_token(p), 'unit.test-fixture+/with=punctuation')
            for value in ['token\nheader-injection', 'too-short', 'x' * 5000]:
                p.write_text(value)
                with self.assertRaises(ValueError): status.read_token(p)


if __name__ == '__main__': unittest.main()
