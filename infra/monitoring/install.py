"""Run root-owned reviewed source on the host: verify first, then deploy.

Never restarts BE/AI, edits their environment, changes RDS or reconfigures Jobs.
Only this stack, its collector service and two nginx includes are managed.
"""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

import stack

SOURCE=Path(__file__).resolve().parent
BOOT=Path('/opt/yamu/monitoring-bootstrap-342')
LIVE=Path('/opt/yamu/monitoring')
PRIVATE=Path('/etc/yamu-monitoring')
DATA=Path('/var/lib/yamu-monitoring')


def source_digest(directory=SOURCE):
    value=hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        if not path.is_file() or '__pycache__' in path.parts or path.name in ('README.md','.gitignore','generate.py') or path.name.startswith('test_'):
            continue
        value.update(str(path.relative_to(directory)).encode()+b'\0'+path.read_bytes()+b'\0')
    return value.hexdigest()


def collector_config(source,root,nginx):
    info=json.loads(stack.run(['docker','inspect','yamu-ci-jenkins-1']))[0]
    homes=[m['Source'] for m in info['Mounts'] if m['Destination']=='/var/jenkins_home' and m['Type']=='volume']
    if len(homes)!=1:raise ValueError('Expected Jenkins home volume')
    return {'root':str(root),'routes_file':str(source/'routes.json'),
            'jenkins_home':homes[0],'nginx_log':str(nginx)}


def application_identity():
    result={}
    for name in ('yamu-prod-backend-1','yamu-prod-ai-1'):
        item=json.loads(stack.run(['docker','inspect',name]))[0]
        result[name]=(item['Id'],item['Image'],item['State']['StartedAt'])
    return result


def ensure_modes(source):
    for path in source.rglob('*'):
        if path.is_symlink():raise ValueError('Source symlinks forbidden')
        if path.stat().st_uid!=0 or path.stat().st_mode & 0o022:raise ValueError('Source must be root-owned, not writable by others')
        path.chmod(0o755 if path.is_dir() else 0o644)


def verify():
    root=BOOT/('stage-'+uuid.uuid4().hex[:8]);root.mkdir(mode=0o700)
    project='yamu-monitoring-test-'+uuid.uuid4().hex[:8]
    data=root/'data';fixture=root/'fixture-api.log'
    fixture.write_text(''.join(json.dumps({'route':'/api/health','method':'GET','status':status,'duration':duration})+'\n' for status,duration in ((200,.02),(401,.1),(500,.4))))
    config=collector_config(SOURCE,data,fixture)
    stack.private(root/'collector.json',json.dumps(config))
    code="import sys,json,importlib.util,time; spec=importlib.util.spec_from_file_location('collector',sys.argv[1]); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); c=m.Collector(json.load(open(sys.argv[2])));\nwhile True:\n c.cycle(); time.sleep(5)"
    credentials=stack.prepare(SOURCE,root,13001,data/'public')
    before=application_identity();collector=None;started=False
    result={'passed':False,'synthetic_api_fixture':True,'production_deployment':False,'source_sha256':source_digest()}
    try:
        collector=subprocess.Popen([sys.executable,'-u','-c',code,str(SOURCE/'collector.py'),str(root/'collector.json')],stdout=open(root/'collector.log','wb'),stderr=subprocess.STDOUT)
        for _ in range(20):
            if (data/'public/metrics/yamu.prom').exists():break
            time.sleep(1)
        else:raise ValueError('Staging collector failed')
        stack.compose(SOURCE,root,project,'config','--quiet')
        stack.compose(SOURCE,root,project,'up','-d','--pull','never');started=True
        stack.wait_ready(13001,credentials);stack.viewer_setup(13001,credentials)
        result['checks']=stack.verify(13001,credentials)
        ids=stack.compose(SOURCE,root,project,'ps','-q').decode().splitlines()
        for identifier in ids:
            info=json.loads(stack.run(['docker','inspect',identifier]))[0]
            ports=info['HostConfig'].get('PortBindings') or {}
            if info['Config']['Labels']['com.docker.compose.service']=='grafana':
                if any(p['HostIp']!='127.0.0.1' for values in ports.values() for p in values):raise ValueError('Grafana must bind loopback')
            elif ports:raise ValueError('Internal monitoring service published a port')
        result['checks']['only_grafana_loopback_port_published']=True
        # Actual service restart, persistent volumes and provisioned dashboard remain readable.
        stack.compose(SOURCE,root,project,'restart','grafana');stack.wait_ready(13001,credentials)
        status,_=stack.api(13001,credentials,'api/dashboards/uid/yamu-team',viewer=True)
        result['checks']['grafana_restart_preserves_viewer_and_dashboard']=status==200
        if not all(result['checks'].values()):raise ValueError('Restart check failed')
        result['passed']=True
    except Exception as error:
        result['error_type']=type(error).__name__
        result['error_step']=str(error) if isinstance(error,ValueError) else 'details suppressed'
        raise
    finally:
        if started:
            (root/'stack.log').write_bytes(stack.compose(SOURCE,root,project,'logs','--no-color','--tail','80'))
            stack.compose(SOURCE,root,project,'down','--volumes','--remove-orphans')
        if collector:
            collector.terminate();collector.wait(timeout=20)
        result['production_be_ai_unchanged']=before==application_identity()
        result['temporary_stack_removed']=True
        for path in ('credentials.json','admin-password'): (root/path).unlink(missing_ok=True)
        stack.private(BOOT/'verification.json',json.dumps(result,indent=2))
    print(json.dumps(result))


def deploy():
    evidence=json.loads((BOOT/'verification.json').read_text())
    if not evidence.get('passed'):raise ValueError('Successful stack verification required')
    if evidence.get('source_sha256')!=source_digest():raise ValueError('Source changed after verification')
    resume=LIVE.exists() and PRIVATE.exists()
    if LIVE.exists()!=PRIVATE.exists():raise ValueError('Partial directories require operator inspection')
    if resume:
        ensure_modes(LIVE)
        if source_digest(LIVE)!=source_digest():raise ValueError('Installed source differs; review update separately')
        installed=json.loads((PRIVATE/'config.json').read_text())
        if installed.get('root')!=str(DATA) or installed.get('nginx_log')!='/var/log/nginx/yamu-api-monitor.log':
            raise ValueError('Existing collector is not this managed installation')
    before=application_identity()
    site=Path('/etc/nginx/sites-enabled/yamu').resolve()
    if site!=Path('/etc/nginx/sites-available/yamu'):raise ValueError('Unexpected nginx target')
    previous=site.read_text()
    if previous.count('location /api/ {')!=1 or 'listen 443 ssl;' not in previous:raise ValueError('Unexpected HTTPS config')
    if '/monitoring/' in previous:raise ValueError('Existing monitoring route')
    includes=[Path('/etc/nginx/conf.d/yamu-monitoring.conf'),Path('/etc/nginx/snippets/yamu-monitoring.conf')]
    if any(path.exists() for path in includes):raise ValueError('Existing include; do not overwrite')
    if not resume:
        shutil.copytree(SOURCE,LIVE,ignore=shutil.ignore_patterns('__pycache__'))
        ensure_modes(LIVE)
        PRIVATE.mkdir(mode=0o700)
        credentials=stack.prepare(LIVE,PRIVATE,13000,DATA/'public')
        config=collector_config(LIVE,DATA,Path('/var/log/nginx/yamu-api-monitor.log'))
        stack.private(PRIVATE/'config.json',json.dumps(config))
        code=Path('/usr/local/lib/yamu-monitoring');code.mkdir(mode=0o755)
        shutil.copy(LIVE/'collector.py',code/'collector.py');shutil.copy(LIVE/'routes.json',code/'routes.json')
        config['routes_file']=str(code/'routes.json');stack.private(PRIVATE/'config.json',json.dumps(config))
        DATA.mkdir(mode=0o755,exist_ok=True)
        shutil.copy(LIVE/'yamu-monitoring.service','/etc/systemd/system/yamu-monitoring.service')
        stack.run(['systemctl','daemon-reload']);stack.run(['systemctl','enable','--now','yamu-monitoring.service'])
    else:
        credentials=json.loads((PRIVATE/'credentials.json').read_text())
    for _ in range(20):
        if (DATA/'public/metrics/yamu.prom').exists():break
        time.sleep(1)
    else:raise ValueError('Production collector readiness failed')
    if not resume:stack.compose(LIVE,PRIVATE,'yamu-monitoring','up','-d','--pull','never')
    print('Verifying prepared private stack before public proxy connection.',flush=True)
    stack.wait_ready(13000,credentials);stack.viewer_setup(13000,credentials)
    # Verify stack before connecting it to the public reverse proxy.
    preliminary=stack.verify(13000,credentials,require_api_metrics=False)
    print('Private stack verified; applying nginx includes with rollback protection.',flush=True)
    backup=BOOT/'nginx-before-monitoring.conf';stack.private(backup,previous)
    new=previous.replace('    location /api/ {','    location /api/ {\n        access_log /var/log/nginx/access.log combined;\n        access_log /var/log/nginx/yamu-api-monitor.log yamu_monitor;',1)
    new=new.replace('    server_name yamu.it.kr;','    server_name yamu.it.kr;\n    include /etc/nginx/snippets/yamu-monitoring.conf;',1)
    try:
        shutil.copy(LIVE/'nginx-http.conf',includes[0]);shutil.copy(LIVE/'nginx-server.conf',includes[1])
        site.write_text(new)
        stack.run(['nginx','-t']);stack.run(['systemctl','reload','nginx'])
        # reload is asynchronous. Do not mistake the old POS SPA's HTTP200 for Grafana.
        for _ in range(30):
            try:
                with urllib.request.urlopen('https://yamu.it.kr/monitoring/api/health',timeout=10) as response:
                    grafana_health=json.load(response)
                    if response.status==200 and grafana_health.get('database')=='ok':break
            except (ValueError, OSError):pass
            time.sleep(1)
        else:raise ValueError('Public Grafana route readiness failed')
        with urllib.request.urlopen('https://yamu.it.kr/api/health',timeout=20) as response:
            if json.load(response).get('status')!='ok':raise ValueError('Existing API health changed')
        try:urllib.request.urlopen('https://yamu.it.kr/api/users/me',timeout=20)
        except urllib.error.HTTPError as error:
            if error.code!=401:raise
        with urllib.request.urlopen('https://yamu.it.kr/monitoring/login',timeout=20) as response:
            if response.status!=200:raise ValueError('Public monitoring login failed')
        time.sleep(20)
        result={'passed':True,'url':'https://yamu.it.kr/monitoring/','checks':stack.verify(13000,credentials),
                'production_be_ai_unchanged':before==application_identity(),
                'public_login_https':True,'public_grafana_api_verified':True,'existing_api_health_ok':True,
                'source_sha256':source_digest(),
                'api_source':'nginx normalized metadata; no BE code changes',
                'shared_viewer_login':'yamu-team','credentials_file':'/etc/yamu-monitoring/credentials.json'}
        if not result['production_be_ai_unchanged']:raise ValueError('Unexpected application restart')
        stack.private(BOOT/'deployment.json',json.dumps(result,indent=2))
        print(json.dumps(result))
    except Exception:
        site.write_text(previous)
        for path in includes:path.unlink(missing_ok=True)
        stack.run(['nginx','-t']);stack.run(['systemctl','reload','nginx'])
        raise


if __name__=='__main__':
    try:
        if os.geteuid()!=0:raise ValueError('Root-owned host setup required')
        if sys.argv[1:] not in (['verify'],['deploy']):raise ValueError('Use verify or deploy')
        ensure_modes(SOURCE)
        verify() if sys.argv[1]=='verify' else deploy()
    except Exception as error:
        print('Monitoring setup stopped: '+type(error).__name__)
        if isinstance(error,ValueError):print(str(error))
        raise SystemExit(1)
