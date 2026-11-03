"""Fixed-project GitLab status reporting; no candidate code or secret value is executed/logged."""
import argparse
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = 'https://lab.ssafy.com/api/v4'
PROJECT_PATH = 's15-final/S15P31E103'
PROJECT = urllib.parse.quote(PROJECT_PATH, safe='')
STATES = {'pending', 'running', 'success', 'failed', 'canceled', 'skipped'}
MR_CONTEXT = 'jenkins/mr-ci'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('GitLab API redirects are forbidden')


def sha(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('Invalid checkout revision')
    return value


def ref(value):
    if (not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]{0,199}', value)
            or any(x in value for x in ('..', '//', '/.', '@{')) or value.endswith(('/', '.', '.lock'))):
        raise ValueError('Invalid source branch')
    return value


def build_url(value):
    if not re.fullmatch(r'http://100\.102\.125\.41:8081/job/yamu/(?:job/gitlab/)?job/(?:ci|mr-ci|mr-ci-verification)/[1-9][0-9]*/', value or ''):
        raise ValueError('Unexpected Jenkins build link')
    return value


def read_token(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
        raise ValueError('Expected a private token file')
    if os.name == 'posix' and path.stat().st_mode & 0o077:
        raise ValueError('Token file must have no group/other permissions')
    token = path.read_text(encoding='utf-8-sig').strip()
    # Accept GitLab/version-specific token alphabets, but never header whitespace/control injection.
    if not re.fullmatch(r'[!-~]{16,1024}', token):
        raise ValueError('Token file must contain only the API token')
    return token


class Client:
    def __init__(self, token, opener=None, sleep=time.sleep):
        self.token = token
        self.opener = opener or urllib.request.build_opener(NoRedirect())
        self.sleep = sleep

    def request(self, method, suffix='', values=None):
        url = API + '/projects/' + PROJECT + suffix
        data = None
        if values:
            encoded = urllib.parse.urlencode(values)
            if method == 'GET': url += '?' + encoded
            else: data = encoded.encode()
        request = urllib.request.Request(url, data=data, method=method,
            headers={'PRIVATE-TOKEN': self.token, 'Content-Type': 'application/x-www-form-urlencoded'})
        for attempt in range(3):
            try:
                with self.opener.open(request, timeout=12) as response:
                    body = response.read(2 * 1024 * 1024 + 1)
                    if len(body) > 2 * 1024 * 1024: raise ValueError('API response is too large')
                    return json.loads(body)
            except urllib.error.HTTPError as error:
                if error.code not in (409, 429, 500, 502, 503, 504) or attempt == 2: raise
            except (TimeoutError, urllib.error.URLError):
                if attempt == 2: raise
            self.sleep(attempt + 1)

    def project(self):
        project = self.request('GET')
        if project.get('path_with_namespace') != PROJECT_PATH or not isinstance(project.get('id'), int):
            raise ValueError('Unexpected GitLab project')
        return project['id']

    def mr(self, iid):
        if not re.fullmatch('[1-9][0-9]*', str(iid)): raise ValueError('Invalid MR number')
        return self.request('GET', '/merge_requests/' + str(iid))

    def opened(self):
        for page in range(1, 21):
            values = self.request('GET', '/merge_requests', {'state': 'opened', 'target_branch': 'develop',
                'order_by': 'updated_at', 'sort': 'asc', 'per_page': 100, 'page': page})
            if not isinstance(values, list): raise ValueError('Invalid MR list')
            yield from values
            if len(values) < 100: return
        raise ValueError('MR pagination limit reached')

    def latest(self, revision, branch):
        values = self.request('GET', '/repository/commits/' + sha(revision) + '/statuses',
            {'ref': ref(branch), 'name': MR_CONTEXT, 'all': 'true', 'per_page': 100})
        if not isinstance(values, list): raise ValueError('Invalid status list')
        matches = [s for s in values if s.get('name') == MR_CONTEXT and s.get('ref') == branch]
        return max(matches, key=lambda s: int(s['id']), default=None)

    def report(self, revision, branch, state, context, url, description):
        if state not in STATES or context not in (MR_CONTEXT, 'jenkins/develop-ci'):
            raise ValueError('Unexpected status or context')
        return self.request('POST', '/statuses/' + sha(revision), {'state': state, 'ref': ref(branch),
            'name': context, 'target_url': build_url(url), 'description': description[:255]})


def eligible(mr, project_id):
    return (mr.get('state') == 'opened' and mr.get('source_project_id') == project_id
            and mr.get('target_project_id') == project_id and mr.get('target_branch') == 'develop')


def mr_revision(mr):
    return sha(mr.get('sha') or (mr.get('diff_refs') or {}).get('head_sha'))


def choose(client, project_id, iid='', retry=False):
    if retry and not iid: raise ValueError('Manual retry requires an explicit MR number')
    candidates = [client.mr(iid)] if iid else client.opened()
    for summary in candidates:
        if not eligible(summary, project_id): continue
        mr = client.mr(summary['iid'])
        if not eligible(mr, project_id): continue
        revision = mr_revision(mr)
        branch = ref(mr['source_branch'])
        status = client.latest(revision, branch)
        if status and (not retry or status.get('status') in ('pending', 'running')): continue
        return {'iid': int(mr['iid']), 'project_id': project_id, 'sha': revision, 'ref': branch}
    return None


def validate_context(context, expected_sha, expected_ref, expected_iid):
    if context.get('sha') != sha(expected_sha): raise ValueError('Pinned source revision changed')
    sha(context['sha']); ref(context['ref'])
    if not isinstance(context.get('iid'), int) or context['iid'] <= 0 or not isinstance(context.get('project_id'), int):
        raise ValueError('Invalid MR context')
    if context['ref'] != expected_ref or str(context['iid']) != str(expected_iid):
        raise ValueError('Pinned MR identity changed')


def final_state(context, current, requested):
    if requested not in STATES: raise ValueError('Invalid requested state')
    if not eligible(current, context['project_id']) or mr_revision(current) != context['sha']:
        return 'canceled'
    return requested


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['select', 'mr-report', 'develop-report'])
    parser.add_argument('--context', type=Path, default=Path('gitlab-status/context.json'))
    args = parser.parse_args()
    env = os.environ
    client = Client(read_token(env['YAMU_GITLAB_API_FILE']))
    project_id = client.project()
    if args.action == 'select':
        context = choose(client, project_id, env.get('YAMU_MR_IID', ''), env.get('YAMU_RETRY_MR') == 'true')
        if context is None:
            print('none'); return
        args.context.parent.mkdir(parents=True, exist_ok=True)
        if args.context.exists(): raise ValueError('Stale context file')
        args.context.write_text(json.dumps(context))
        print('selected')
    elif args.action == 'mr-report':
        context = json.loads(args.context.read_text())
        validate_context(context, env['YAMU_PINNED_SHA'], env['YAMU_PINNED_REF'], env['YAMU_PINNED_IID'])
        if context['project_id'] != project_id: raise ValueError('Context project changed')
        state = env['YAMU_GITLAB_STATE']
        current = client.mr(context['iid'])
        state = final_state(context, current, state)
        client.report(context['sha'], context['ref'], state, MR_CONTEXT, env['BUILD_URL'], 'Jenkins MR validation: ' + state)
        print('GitLab MR status sent.')
    else:
        if env.get('JOB_NAME') != 'yamu/ci' or env.get('GIT_BRANCH') != 'origin/develop':
            raise ValueError('Only the actual develop CI job may report develop results')
        client.report(sha(env['GIT_COMMIT']), 'develop', env['YAMU_GITLAB_STATE'], 'jenkins/develop-ci',
            env['BUILD_URL'], 'Jenkins develop CI: ' + env['YAMU_GITLAB_STATE'])
        print('GitLab develop status sent.')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Never print token, URL, headers, API bodies, exception strings or tracebacks.
        print(json.dumps({'gitlab_status_ok': False, 'error_type': type(error).__name__,
                          'http_status': error.code if isinstance(error, urllib.error.HTTPError) else None}))
        raise SystemExit(1)
