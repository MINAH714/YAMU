"""Generate bounded nginx route labels and the provisioned team dashboard."""
import json
from pathlib import Path
import re

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]


def main():
    routes={'/api/health'}
    for file in (ROOT/'be/src/main/java').rglob('*Controller.java'):
        source=file.read_text(encoding='utf-8')
        before,_,body=source.partition('public class ')
        parent=re.search(r'@RequestMapping\(\s*"(/api[^"\s]*)"',before)
        if not parent:continue
        for match in re.finditer(r'@(?:Get|Post|Put|Patch|Delete)Mapping(?:\((.*?)\))?',body,re.S):
            children=[p for p in re.findall(r'"([^"\n]*)"',match.group(1) or '') if p.startswith('/')]
            for child in children or ['']:
                path=parent.group(1).rstrip('/')+child
                if re.fullmatch(r'/api/[A-Za-z0-9_/{}/.:-]+',path):routes.add(path)
    ordered=sorted(routes,key=lambda p:('{' in p,-len(p),p))
    (HERE/'routes.json').write_text(json.dumps(ordered,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    mappings=[]
    for route in ordered:
        expression='^'+re.sub(r'\{[^}]+\}', '[^/]+', route)+'$'
        # Generated labels contain no request values, parameters, IPs or headers.
        mappings.append('    '+json.dumps('~'+expression)+' '+json.dumps(route)+';')
    config='''# http context only. Generated from route templates; no user values logged.
map $uri $yamu_monitor_route {
    default "/api/other";
'''+ '\n'.join(mappings)+'''
}
map $http_upgrade $yamu_monitor_connection {
    default upgrade;
    '' close;
}
log_format yamu_monitor escape=json
    '{"route":"$yamu_monitor_route","method":"$request_method","status":$status,"duration":$request_time}';
'''
    (HERE/'nginx-http.conf').write_text(config,encoding='utf-8')
    dashboard()


def dashboard():
    panels=[]
    def panel(title,kind,expr,x,y,w=8,h=7,unit=None,loki=False):
        target={'refId':'A','expr':expr,'datasource':{'type':'loki' if loki else 'prometheus','uid':'yamu-loki' if loki else 'yamu-prometheus'}}
        if kind=='table':target.update({'format':'table','instant':True})
        p={'id':len(panels)+1,'title':title,'type':kind,'gridPos':{'x':x,'y':y,'w':w,'h':h},
           'datasource':target['datasource'],'targets':[target],
           'fieldConfig':{'defaults':{'unit':unit or 'short'},'overrides':[]},'options':{}}
        if kind=='stat':p['options']={'reduceOptions':{'calcs':['lastNotNull']},'colorMode':'value','graphMode':'area'}
        if kind=='logs':p['options']={'showTime':True,'wrapLogMessage':True,'sortOrder':'Descending','enableLogDetails':True}
        panels.append(p)
    panel('수집 신선도 · 30초 이상이면 수집 확인','stat','time() - yamu_collector_last_success_timestamp_seconds',0,0,6,4,'s')
    panel('운영 BE 상태 · 1 정상 / 0 확인 필요','stat','yamu_service_up{service="backend"}',6,0,6,4)
    panel('운영 AI 상태 · 1 정상 / 0 확인 필요','stat','yamu_service_up{service="ai"}',12,0,6,4)
    panel('관측 API 요청 누계 · 설치 이후','stat','sum(yamu_api_requests_total) or vector(0)',18,0,6,4)
    panel('API 요청 / 초','timeseries','sum by (route) (rate(yamu_api_requests_total[5m]))',0,4,12,7,'reqps')
    panel('HTTP 상태별 요청 / 초 · 4xx도 함께 확인','timeseries','sum by (status) (rate(yamu_api_requests_total[5m]))',12,4,12,7,'reqps')
    panel('API p95 응답시간 · Nginx 기준','timeseries','histogram_quantile(0.95, sum by (le, route) (rate(yamu_api_request_duration_seconds_bucket[5m])))',0,11,12,7,'s')
    panel('서버 CPU 사용률','timeseries','yamu_host_cpu_busy_ratio',12,11,12,7,'percentunit')
    panel('서버 가용 메모리','timeseries','yamu_host_memory_available_bytes',0,18,8,6,'bytes')
    panel('서버 가용 디스크','timeseries','yamu_host_disk_available_bytes',8,18,8,6,'bytes')
    panel('로그 누락 감지 누계 · 증가 시 회전/폭주 확인','stat','yamu_collector_log_gaps_total',16,18,8,6)
    panel('현재 배포 이미지 / 커밋','table','yamu_service_info',0,24,24,6)
    panel('Jenkins 최근 빌드 결과 · ci / cd / verification','table','yamu_jenkins_build_result',0,30,16,7)
    panel('Jenkins 빌드 소요시간','table','yamu_jenkins_build_duration_seconds',16,30,8,7,'s')
    panel('API 요청 로그 · 값/헤더/본문 없이 경로 템플릿만','logs','{service="api"}',0,37,24,9,loki=True)
    panel('BE · AI 실행 로그 · 검색은 Explore에서도 가능','logs','{service=~"backend|ai"}',0,46,24,12,loki=True)
    panel('Jenkins CI/CD 콘솔 로그 · 수집 시작 시 마지막 일부부터','logs','{service=~"jenkins-.+"}',0,58,24,12,loki=True)
    panel('배포 관측 이벤트 · 최초 관측은 배포 성공 판정 아님','logs','{service="deployments"}',0,70,24,9,loki=True)
    data={'uid':'yamu-team','title':'YAMU · API와 배포 모니터링','tags':['YAMU'],'schemaVersion':39,'version':1,
          'timezone':'Asia/Seoul','refresh':'15s','time':{'from':'now-1h','to':'now'},'editable':False,
          'panels':panels,'annotations':{'list':[]},'templating':{'list':[]},
          'links':[{'title':'운영 API health','url':'https://yamu.it.kr/api/health','targetBlank':True}]}
    folder=HERE/'grafana/dashboards';folder.mkdir(parents=True,exist_ok=True)
    (folder/'yamu.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':main()
