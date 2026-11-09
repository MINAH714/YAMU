import contextlib
import io
import json
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

import notify_mattermost as notify


def environment(**changes):
    env = {'YAMU_NOTIFY_KIND': 'CI', 'YAMU_NOTIFY_EVENT': 'SUCCESS',
           'JOB_NAME': 'yamu/ci', 'BUILD_NUMBER': '12',
           'BUILD_URL': 'http://jenkins.example/job/yamu/job/ci/12/',
           'GIT_COMMIT': 'a' * 40, 'MATTERMOST_WEBHOOK': 'https://chat.example/hooks/secret123',
           'DB_PASSWORD': 'must-not-appear', 'TESTED_BUILD': ''}
    env.update(changes)
    return env


class NotificationsTest(unittest.TestCase):
    def test_ci_and_cd_payloads_include_only_expected_metadata(self):
        for event in notify.EVENTS:
            with self.subTest(event=event):
                env = environment(YAMU_NOTIFY_KIND='CD', YAMU_NOTIFY_EVENT=event,
                                  JOB_NAME='yamu/cd', TESTED_BUILD='25')
                data = notify.payload(env)
                text = json.dumps(data, ensure_ascii=False)
                self.assertIn('ci-master #25', text)
                self.assertNotIn('secret123', text)
                self.assertNotIn('must-not-appear', text)
                self.assertNotIn('channel', data)
        with self.assertRaises(ValueError):
            notify.payload(environment(YAMU_NOTIFY_EVENT='APPROVAL_PENDING'))
        with self.assertRaises(KeyError):
            notify.payload(environment(YAMU_NOTIFY_EVENT='UNKNOWN'))

    def test_metadata_cannot_inject_mentions_or_markup(self):
        data = notify.payload(environment(JOB_NAME='@channel\n[link]<tag>`code`',
                                         GIT_COMMIT='bad revision'))
        text = data['attachments'][0]['text']
        self.assertNotIn('@channel', text)
        self.assertNotIn('[link]', text)
        self.assertNotIn('커밋:', text)

    def test_webhook_must_use_https_without_auth_query_or_redirect(self):
        invalid = ['http://chat.example/hooks/token', 'https://chat.example/api/token',
                   'https://user:pass@chat.example/hooks/token',
                   'https://chat.example/hooks/token?key=secret',
                   'https://chat.example/hooks/token#secret']
        with patch.object(notify.urllib.request, 'build_opener') as opener:
            for url in invalid:
                with self.subTest(url=url), self.assertRaises(ValueError):
                    notify.send(url, {})
            opener.assert_not_called()
        with self.assertRaises(ValueError):
            notify.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere.example')

    def test_json_delivery_is_bounded_and_not_retried(self):
        response = MagicMock()
        response.status = 200
        response.read.return_value = b'ok'
        response.__enter__.return_value = response
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(notify.urllib.request, 'build_opener', return_value=opener):
            data = notify.payload(environment())
            notify.send(environment()['MATTERMOST_WEBHOOK'], data)
        request = opener.open.call_args.args[0]
        self.assertEqual('POST', request.get_method())
        self.assertEqual(data, json.loads(request.data))
        self.assertEqual(8, opener.open.call_args.kwargs['timeout'])
        opener.open.assert_called_once()

    def test_transport_failure_does_not_leak_secret_or_fail_build(self):
        secret = environment()['MATTERMOST_WEBHOOK']
        errors = [TimeoutError(secret), urllib.error.HTTPError(secret, 500, secret, {}, None)]
        for error in errors:
            with self.subTest(error=type(error).__name__), patch.object(notify, 'send', side_effect=error) as send:
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(0, notify.main(environment()))
                self.assertIn('WARNING', output.getvalue())
                self.assertNotIn(secret, output.getvalue())
                send.assert_called_once()

    def test_missing_credentials_and_invalid_ack_are_best_effort(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, notify.main({}))
        response = MagicMock()
        response.status = 200
        response.read.return_value = b'not accepted'
        response.__enter__.return_value = response
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(notify.urllib.request, 'build_opener', return_value=opener), self.assertRaises(ValueError):
            notify.send(environment()['MATTERMOST_WEBHOOK'], {})


if __name__ == '__main__':
    unittest.main()
