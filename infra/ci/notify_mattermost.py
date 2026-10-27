"""Send bounded, best-effort build notifications; never log webhook URLs."""
import json
import os
import re
import urllib.parse
import urllib.request


EVENTS = {
    'SUCCESS': ('성공', '#2e8b57'),
    'FAILURE': ('실패', '#cc3333'),
    'UNSTABLE': ('검사 불안정', '#d99a00'),
    'ABORTED': ('중단', '#808080'),
    'APPROVAL_PENDING': ('BE 배포 승인 대기', '#d99a00'),
    'TEST': ('알림 연결 검증 — 배포 없음', '#3366cc'),
}


def clean(value):
    return re.sub(r'[\r\n\[\]<>`]', ' ', str(value)).replace('@', '＠')[:180]


def payload(env):
    event = env['YAMU_NOTIFY_EVENT']
    title, color = EVENTS[event]
    kind = env['YAMU_NOTIFY_KIND']
    if kind not in ('CI', 'CD') or event == 'APPROVAL_PENDING' and kind != 'CD':
        raise ValueError('Invalid notification kind')
    url = urllib.parse.urlsplit(env['BUILD_URL'])
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
        raise ValueError('Invalid build link')
    lines = [f"{clean(env['JOB_NAME'])} #{clean(env['BUILD_NUMBER'])}"]
    revision = env.get('GIT_COMMIT', '')
    if re.fullmatch(r'[0-9a-f]{40}', revision):
        lines.append('커밋: ' + revision[:12])
    candidate = env.get('TESTED_BUILD', '')
    if kind == 'CD' and re.fullmatch(r'[1-9][0-9]*', candidate):
        lines.append('후보: ci-master #' + candidate)
    if event == 'APPROVAL_PENDING':
        lines.append('Jenkins에서 yamu 승인 필요 · 승인 전에는 배포하지 않습니다.')
    # No channel override: use the channel locked to the incoming webhook.
    return {'attachments': [{'title': f'YAMU {kind} · {title}',
                             'title_link': env['BUILD_URL'], 'color': color,
                             'text': '\n'.join(lines)}]}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Webhook redirects are not allowed')


def send(webhook, data):
    url = urllib.parse.urlsplit(webhook)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or not re.fullmatch(r'/hooks/[A-Za-z0-9]+', url.path)):
        raise ValueError('Invalid incoming webhook')
    request = urllib.request.Request(webhook, data=json.dumps(data).encode('utf-8'),
                                     headers={'Content-Type': 'application/json'}, method='POST')
    # Do not retry: a timeout can mean the server accepted the message already.
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=8) as response:
        if response.status != 200 or response.read(64).strip() != b'ok':
            raise ValueError('Notification was not acknowledged')


def main(env=None):
    env = os.environ if env is None else env
    try:
        send(env['MATTERMOST_WEBHOOK'], payload(env))
        print('Mattermost notification sent.')
    except Exception:
        # Exceptions can contain the secret URL or response content.
        print('WARNING: Mattermost notification unavailable; build result unchanged.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
