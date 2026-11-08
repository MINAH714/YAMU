"""Read-only YAMU portal gateway. Existing Grafana cookie authentication only.

No passwords, service credentials, new user database, arbitrary upstream URLs,
PromQL or LogQL are accepted. Only fixed queries and bounded log selectors.
"""
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE=os.environ.get('GRAFANA_URL','http://grafana:3000/monitoring/').rstrip('/')+'/'
WEB=Path(__file__).parent/'web'
PREFIX='/monitoring/status-api/'
SOURCES={'api','backend','ai','jenkins-ci','jenkins-cd','jenkins-verification','deployments'}
WINDOWS={'15m':900,'1h':3600,'6h':21600}
COOKIE_NAMES={'grafana_session','grafana_session_expiry'}
CAPACITY=threading.BoundedSemaphore(16)
RAW_QUERY='{__name__=~"'+'|'.join(('yamu_service_up','yamu_service_info','yamu_host_cpu_busy_ratio',
    'yamu_host_memory_available_bytes','yamu_host_memory_total_bytes','yamu_host_disk_available_bytes',
    'yamu_collector_last_success_timestamp_seconds','yamu_api_requests_total',
    'yamu_jenkins_build_result','yamu_jenkins_build_duration_seconds','yamu_jenkins_build_timestamp_seconds'))+'"}'


class AuthRequired(Exception):pass
class Unavailable(Exception):pass
class BadInput(Exception):pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


def number(value):
    try:
        parsed=float(value)
        return parsed if math.isfinite(parsed) else None
    except (TypeError,ValueError):return None


def parse_options(query,logs=False):
    values=urllib.parse.parse_qs(query,keep_blank_values=True)
    allowed={'window','source','search','errors','build'} if logs else {'window'}
    if set(values)-allowed or any(len(v)!=1 for v in values.values()):raise BadInput()
    result={key:value[0] for key,value in values.items()}
    result.setdefault('window','15m')
    if result['window'] not in WINDOWS:raise BadInput()
    if logs:
        result.setdefault('source','api');result.setdefault('search','');result.setdefault('errors','0')
        if result['source'] not in SOURCES or len(result['search'])>100 or any(ord(c)<32 for c in result['search']):raise BadInput()
        if result['errors'] not in ('0','1'):raise BadInput()
        result.setdefault('build','')
        if result['build'] and (not result['source'].startswith('jenkins-') or not result['build'].isdigit() or len(result['build'])>9):raise BadInput()
    return result


def log_query(options):
    query='{service='+json.dumps(options['source'])+'}'
    if options.get('build'):query+=' | json | build='+json.dumps(options['build'])
    if options['errors']=='1':
        query+=' | json | status >= 400' if options['source']=='api' else ' |~ "(?i)error|exception|warn|failure|failed"'
    if options['search']:query+=' |= '+json.dumps(options['search'])
    return query


class Grafana:
    def __init__(self,cookie,deadline=None):
        self.cookies={};self.changed={};self.deadline=deadline or time.monotonic()+12
        if len(cookie)>8192:raise AuthRequired()
        try:
            parsed=SimpleCookie();parsed.load(cookie)
            self.cookies={key:item.value for key,item in parsed.items() if key in COOKIE_NAMES}
        except Exception:raise AuthRequired() from None
        if not self.cookies.get('grafana_session'):raise AuthRequired()
        self.opener=urllib.request.build_opener(NoRedirect())

    def get(self,path):
        # Even internal callers cannot turn this into a general reverse proxy.
        allowed=('api/user','api/datasources/proxy/uid/yamu-prometheus/api/v1/query',
                 'api/datasources/proxy/uid/yamu-prometheus/api/v1/query_range',
                 'api/datasources/proxy/uid/yamu-loki/loki/api/v1/query_range')
        if path.split('?',1)[0] not in allowed:raise BadInput()
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise Unavailable()
        request=urllib.request.Request(BASE+path,headers={'Cookie':'; '.join(k+'='+v for k,v in self.cookies.items()),'Accept':'application/json'})
        try:
            with self.opener.open(request,timeout=min(4,remaining)) as response:
                for header in response.headers.get_all('Set-Cookie',[]):
                    if '\r' in header or '\n' in header or len(header)>8192:continue
                    parsed=SimpleCookie();parsed.load(header)
                    for name,item in parsed.items():
                        if name in COOKIE_NAMES:
                            self.cookies[name]=item.value;self.changed[name]=header
                raw=response.read(4*1024*1024+1)
                if len(raw)>4*1024*1024:raise Unavailable()
                return json.loads(raw)
        except urllib.error.HTTPError as error:
            if error.code in (401,403):raise AuthRequired() from None
            raise Unavailable() from None
        except (OSError,ValueError):raise Unavailable() from None

    def user(self):
        user=self.get('api/user')
        if not isinstance(user.get('id'),int) or user['id']<=0 or user.get('orgId')!=1:raise AuthRequired()
        return {'login':str(user.get('login',''))[:80]}

    def prometheus(self,query,start=None,end=None):
        params={'query':query}
        endpoint='query'
        if start is not None:
            endpoint='query_range';params.update({'start':start,'end':end,'step':30 if end-start<=3600 else 120})
        data=self.get('api/datasources/proxy/uid/yamu-prometheus/api/v1/'+endpoint+'?'+urllib.parse.urlencode(params))
        if data.get('status')!='success':raise Unavailable()
        return data.get('data',{}).get('result',[])


def overview(client,window):
    now=time.time();errors=[]
    def query(name,expression,start=None):
        try:return client.prometheus(expression,start,now if start else None)
        except Unavailable:errors.append(name);return []
    rows=query('metrics',RAW_QUERY)
    result={'generated_at':now,'services':{},'api':{'total':None,'routes':[]},'host':{},
            'builds':[],'collector_age_seconds':None,'trend':[],'errors':errors}
    counters={};builds={};gauges={}
    for row in rows[:4096]:
        metric=row.get('metric',{});name=metric.get('__name__','');value=number(row.get('value',[0,None])[1])
        if value is None:continue
        if name=='yamu_service_up' and metric.get('service') in ('backend','ai'):
            result['services'].setdefault(metric['service'],{})['up']=value==1
        elif name=='yamu_service_info' and metric.get('service') in ('backend','ai'):
            result['services'].setdefault(metric['service'],{})['revision']=str(metric.get('revision',''))[:100]
        elif name=='yamu_api_requests_total':
            route=str(metric.get('route','/api/other'))[:180];status=str(metric.get('status',''))[:3]
            item=counters.setdefault(route,{'route':route,'total':0,'client_errors':0,'server_errors':0})
            item['total']+=value
            if status.startswith('4'):item['client_errors']+=value
            if status.startswith('5'):item['server_errors']+=value
        elif name.startswith('yamu_jenkins_build_'):
            job=metric.get('exported_job',metric.get('job'));build=str(metric.get('build',''))
            if job not in ('ci','cd','verification') or not build.isdigit():continue
            item=builds.setdefault((job,build),{'job':job,'build':int(build)})
            if name.endswith('_result'):item['result']=str(metric.get('result','UNKNOWN'))[:30]
            if name.endswith('_duration_seconds'):item['duration_seconds']=value
            if name.endswith('_timestamp_seconds'):item['timestamp']=value
        else:gauges[name]=value
    result['api']['routes']=sorted(counters.values(),key=lambda x:(-x['server_errors'],-x['client_errors'],-x['total']))[:100]
    if counters:result['api']['total']=sum(x['total'] for x in counters.values())
    result['builds']=sorted(builds.values(),key=lambda x:(-x['build'],x['job']))[:12]
    stamp=gauges.get('yamu_collector_last_success_timestamp_seconds')
    if stamp is not None:result['collector_age_seconds']=max(0,now-stamp)
    result['host']={'cpu_ratio':gauges.get('yamu_host_cpu_busy_ratio'),
        'memory_available':gauges.get('yamu_host_memory_available_bytes'),
        'memory_total':gauges.get('yamu_host_memory_total_bytes'),'disk_available':gauges.get('yamu_host_disk_available_bytes')}
    for name,expression in (
        ('rps','sum(rate(yamu_api_requests_total[5m]))'),
        ('p95','histogram_quantile(0.95,sum by(le)(rate(yamu_api_request_duration_seconds_bucket[5m])))')):
        values=query(name,expression)
        result['api'][name]=number(values[0]['value'][1]) if values else None
    trend=query('trend','sum(rate(yamu_api_requests_total[5m]))',now-WINDOWS[window])
    if trend:result['trend']=[{'time':number(t),'value':number(v)} for t,v in trend[0].get('values',[])][-200:]
    return result


def logs(client,options):
    now=time.time();params={'query':log_query(options),'start':str(int((now-WINDOWS[options['window']])*1e9)),
                          'end':str(int(now*1e9)),'limit':'200','direction':'backward'}
    data=client.get('api/datasources/proxy/uid/yamu-loki/loki/api/v1/query_range?'+urllib.parse.urlencode(params))
    if data.get('status')!='success':raise Unavailable()
    rows=[]
    for stream in data.get('data',{}).get('result',[])[:100]:
        for timestamp,line in stream.get('values',[])[:200]:
            try:
                item=json.loads(line)
                message=str(item.get('message',''))[:16384]
            except (ValueError,AttributeError):message=str(line)[:16384];item={}
            rows.append({'time_ns':str(timestamp),'message':message,'source':options['source'],
                         'build':item.get('build') if isinstance(item.get('build'),int) else None})
    rows.sort(key=lambda r:int(r['time_ns']),reverse=True)
    return {'rows':rows[:200],'generated_at':now,'limit':200}


class Handler(BaseHTTPRequestHandler):
    server_version='YamuStatus';sys_version=''
    def log_message(self,*args):pass  # No URLs, query text, cookies or passwords in access logs.
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def reply(self,status,data,client=None,content_type='application/json; charset=utf-8'):
        body=json.dumps(data,ensure_ascii=False,allow_nan=False).encode() if content_type.startswith('application/json') else data
        self.send_response(status)
        self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','same-origin')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        if client:
            for cookie in client.changed.values():self.send_header('Set-Cookie',cookie)
        self.end_headers();self.wfile.write(body)

    def do_GET(self):
        client=None
        if not CAPACITY.acquire(blocking=False):self.reply(503,{'error':'busy'});return
        try:
            path=urllib.parse.urlsplit(self.path)
            if path.path=='/healthz':self.reply(200,{'status':'ok'});return
            assets={'/status/':'index.html','/status/app.js':'app.js','/status/style.css':'style.css'}
            if path.path in assets:
                mime={'index.html':'text/html; charset=utf-8','app.js':'text/javascript; charset=utf-8','style.css':'text/css; charset=utf-8'}
                file=assets[path.path];self.reply(200,(WEB/file).read_bytes(),content_type=mime[file]);return
            if path.path not in (PREFIX+'session',PREFIX+'overview',PREFIX+'logs'):
                self.reply(404,{'error':'not_found'});return
            options=parse_options(path.query,logs=path.path.endswith('/logs'))
            client=Grafana(self.headers.get('Cookie',''));user=client.user()
            if path.path.endswith('/session'):self.reply(200,{'user':user},client)
            elif path.path.endswith('/overview'):self.reply(200,overview(client,options['window']),client)
            else:self.reply(200,logs(client,options),client)
        except AuthRequired:self.reply(401,{'error':'login_required'},client)
        except BadInput:self.reply(400,{'error':'invalid_query'},client)
        except Unavailable:self.reply(503,{'error':'source_unavailable'},client)
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception as error:
            print('Portal request stopped: '+type(error).__name__,flush=True)
            self.reply(503,{'error':'temporarily_unavailable'},client)
        finally:CAPACITY.release()

    def do_POST(self):self.reply(405,{'error':'read_only'})
    do_PUT=do_POST
    do_PATCH=do_POST
    do_DELETE=do_POST


if __name__=='__main__':
    httpd=ThreadingHTTPServer(('0.0.0.0',int(os.environ.get('PORT','8080'))),Handler)
    httpd.daemon_threads=True
    print('YAMU status portal started',flush=True)
    httpd.serve_forever()
