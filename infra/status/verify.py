"""Authenticated read-only portal smoke test; credentials/cookies never printed."""
from http.cookies import SimpleCookie
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def verify(base):
    if base!='https://yamu.it.kr' and base not in ('http://127.0.0.1:13010','http://127.0.0.1:13011'):
        raise ValueError('Only the expected portal origins are accepted')
    credentials=json.loads(Path('/etc/yamu-monitoring/credentials.json').read_text())
    body=json.dumps({'user':credentials['viewer_login'],'password':credentials['viewer_password'],'rememberMe':False}).encode()
    request=urllib.request.Request('https://yamu.it.kr/monitoring/login',data=body,
        headers={'Content-Type':'application/json','Origin':'https://yamu.it.kr'},method='POST')
    with urllib.request.urlopen(request,timeout=20) as response:
        cookie=SimpleCookie()
        for header in response.headers.get_all('Set-Cookie',[]):cookie.load(header)
        values={key:item.value for key,item in cookie.items() if key in ('grafana_session','grafana_session_expiry')}
    if not values.get('grafana_session'):raise ValueError('Grafana login session missing')
    checks={}
    def check(name,condition):
        checks[name]=bool(condition)
        if not condition:raise ValueError('Portal check failed: '+name)
    def call(path,params=None,anonymous=False,method='GET'):
        headers={} if anonymous else {'Cookie':'; '.join(k+'='+v for k,v in values.items())}
        url=base+path+('?' + urllib.parse.urlencode(params) if params else '')
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers=headers,method=method),timeout=20) as response:
                for header in response.headers.get_all('Set-Cookie',[]):
                    updated=SimpleCookie();updated.load(header)
                    for name,item in updated.items():
                        if name in values:values[name]=item.value
                raw=response.read()
                try:data=json.loads(raw)
                except ValueError:data=raw.decode()
                return response.status,data
        except urllib.error.HTTPError as error:return error.code,None
    status,data=call('/status/',anonymous=True);check('public_shell_is_portal_not_pos',status==200 and 'YAMU · 운영 모니터링' in data)
    status,_=call('/monitoring/status-api/overview',anonymous=True);check('anonymous_live_data_401',status==401)
    status,data=call('/monitoring/status-api/session');check('existing_grafana_session_reused',status==200 and data['user']['login']==credentials['viewer_login'])
    status,data=call('/monitoring/status-api/overview',{'window':'15m'})
    check('actual_prometheus_data_readable',status==200 and data['api']['total'] is not None and bool(data['services']))
    check('collector_freshness_present',data.get('collector_age_seconds') is not None)
    check('jenkins_results_present',bool(data['builds']))
    check('no_session_cookie_in_payload','grafana_session' not in json.dumps(data))
    for _ in range(10):
        status,data=call('/monitoring/status-api/logs',{'source':'api','window':'15m'})
        if status==200 and data['rows']:break
        time.sleep(2)
    check('actual_loki_api_logs_readable',status==200 and bool(data.get('rows')))
    status,data=call('/monitoring/status-api/logs',{'source':'api','search':'"} |~ ".*','window':'15m'})
    check('logql_search_injection_is_literal',status==200 and not data['rows'])
    status,_=call('/monitoring/status-api/logs',{'source':'untrusted'});check('unknown_source_400',status==400)
    status,_=call('/monitoring/status-api/overview',{'url':'https://untrusted.invalid/'});check('arbitrary_url_400',status==400)
    status,_=call('/monitoring/status-api/overview',method='POST');check('writes_405',status==405)
    status,_=call('/monitoring/status-api/admin');check('arbitrary_proxy_404',status==404)
    return {'passed':True,'checks':checks,'base':base,'credentials_or_cookie_output':False}


if __name__=='__main__':
    try:print(json.dumps(verify(sys.argv[1]),ensure_ascii=False))
    except Exception as error:
        print('Portal verification stopped: '+type(error).__name__)
        if isinstance(error,ValueError):print(str(error))
        raise SystemExit(1)
