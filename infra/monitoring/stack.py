"""Root-only stack setup/test helpers. Passwords are private files, never arguments."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def run(args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.PIPE, **kwargs)


def private(path, text, mode=0o600, group=None):
    with open(path, 'w', encoding='utf-8', opener=lambda p,f:os.open(p,f,mode)) as file:
        file.write(text)
    os.chmod(path,mode)
    if group is not None:os.chown(path,0,group)


def prepare(source, root, port, spool):
    root.mkdir(mode=0o700,parents=True,exist_ok=True)
    credential=root/'credentials.json'
    if credential.exists():
        credentials=json.loads(credential.read_text())
    else:
        credentials={'url':'https://yamu.it.kr/monitoring/',
            'admin_login':'yamu-admin','admin_password':secrets.token_urlsafe(30),
            'viewer_login':'yamu-team','viewer_password':secrets.token_urlsafe(24)}
        private(credential,json.dumps(credentials))
    private(root/'admin-password',credentials['admin_password'],0o440,472)
    images=json.loads((source/'images.lock.json').read_text())
    mapping={'GRAFANA_IMAGE':'grafana/grafana:latest','PROMETHEUS_IMAGE':'prom/prometheus:latest',
             'LOKI_IMAGE':'grafana/loki:latest','ALLOY_IMAGE':'grafana/alloy:latest','NODE_EXPORTER_IMAGE':'prom/node-exporter:latest'}
    values={key:images[value] for key,value in mapping.items()}
    values.update({'GRAFANA_PORT':str(port),'MONITORING_PUBLIC_DIR':str(spool),
                   'MONITORING_ADMIN_PASSWORD_FILE':str(root/'admin-password')})
    if not all('@sha256:' in values[key] for key in mapping):raise ValueError('Images must be digest pinned')
    private(root/'stack.env',''.join(k+'='+v+'\n' for k,v in values.items()))
    return credentials


def compose(source, root, project, *args):
    if not project.startswith('yamu-monitoring'):raise ValueError('Unexpected project')
    return run(['docker','compose','-p',project,'--env-file',str(root/'stack.env'),'-f',str(source/'compose.yml'),*args])


def api(port, credentials, path, method='GET', body=None, viewer=False, anonymous=False):
    headers={}
    if not anonymous:
        prefix='viewer' if viewer else 'admin'
        pair=credentials[prefix+'_login']+':'+credentials[prefix+'_password']
        headers['Authorization']='Basic '+base64.b64encode(pair.encode()).decode()
    if body is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(f'http://127.0.0.1:{port}/monitoring/'+path.lstrip('/'),
        headers=headers,method=method,data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(request,timeout=20) as response:
            text=response.read().decode()
            try:data=json.loads(text)
            except ValueError:data=text
            return response.status,data
    except urllib.error.HTTPError as error:
        # Never return error request objects or headers to logs.
        return error.code,None


def wait_ready(port, credentials):
    for _ in range(90):
        try:
            status,data=api(port,credentials,'api/health')
            if status==200 and data.get('database')=='ok':return
        except Exception:pass
        time.sleep(2)
    raise ValueError('Grafana readiness timeout')


def viewer_setup(port, credentials):
    status,users=api(port,credentials,'api/org/users')
    if status!=200:raise ValueError('Grafana org query failed')
    existing=next((u for u in users if u.get('login')==credentials['viewer_login']),None)
    if existing:
        user_id=existing['userId']
    else:
        status,created=api(port,credentials,'api/admin/users','POST',
            {'name':'YAMU team viewer','login':credentials['viewer_login'],'password':credentials['viewer_password'],'OrgId':1})
        if status not in (200,201):raise ValueError('Viewer creation failed')
        user_id=created['id']
    status,_=api(port,credentials,f'api/org/users/{user_id}','PATCH',{'role':'Viewer'})
    if status!=200:raise ValueError('Viewer role assignment failed')


def verify(port, credentials, require_api_metrics=True):
    checks={}
    def check(name,condition):
        checks[name]=bool(condition)
        if not condition:raise ValueError('Stack check failed: '+name)
    status,_=api(port,credentials,'api/search',anonymous=True);check('anonymous_dashboard_api_401',status==401)
    status,user=api(port,credentials,'api/user',viewer=True)
    check('shared_viewer_login_without_admin',status==200 and not user.get('isGrafanaAdmin'))
    status,dashboard=api(port,credentials,'api/dashboards/uid/yamu-team',viewer=True)
    check('provisioned_dashboard_viewable',status==200 and len(dashboard['dashboard']['panels'])>=15)
    check('viewer_cannot_save_dashboard',not dashboard['meta'].get('canSave',True))
    status,_=api(port,credentials,'api/admin/settings',viewer=True);check('viewer_admin_api_403',status==403)
    status,_=api(port,credentials,'api/dashboards/db','POST',{'dashboard':{'title':'Must not be created','uid':'viewer-must-not-create','panels':[],'schemaVersion':39}},viewer=True)
    check('viewer_dashboard_write_forbidden',status==403)
    for datasource in ('yamu-prometheus','yamu-loki'):
        for _ in range(45):
            status,data=api(port,credentials,f'api/datasources/uid/{datasource}/health',viewer=True)
            if status==200 and data.get('status')=='OK':break
            time.sleep(2)
        check(datasource+'_healthy',status==200 and data.get('status')=='OK')
    for _ in range(20):
        query=urllib.parse.quote('yamu_collector_last_success_timestamp_seconds')
        status,data=api(port,credentials,'api/datasources/proxy/uid/yamu-prometheus/api/v1/query?query='+query,viewer=True)
        if status==200 and data.get('data',{}).get('result'):break
        time.sleep(2)
    check('real_collector_metric_queryable',status==200 and bool(data.get('data',{}).get('result')))
    if require_api_metrics:
        query=urllib.parse.quote('yamu_api_requests_total')
        status,data=api(port,credentials,'api/datasources/proxy/uid/yamu-prometheus/api/v1/query?query='+query,viewer=True)
        check('api_metrics_present',status==200 and bool(data.get('data',{}).get('result')))
    for _ in range(30):
        query=urllib.parse.quote('{service=~"api|deployments|backend|ai"}')
        status,data=api(port,credentials,'api/datasources/proxy/uid/yamu-loki/loki/api/v1/query_range?query='+query+'&limit=10',viewer=True)
        if status==200 and data.get('data',{}).get('result'):break
        time.sleep(2)
    check('logs_queryable_through_grafana_only',status==200 and bool(data.get('data',{}).get('result')))
    return checks
