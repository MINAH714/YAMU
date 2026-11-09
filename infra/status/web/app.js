'use strict';
const $=id=>document.getElementById(id);
const API='/monitoring/status-api/';
const deploymentLogSources = new Set(['jenkins-cd', 'jenkins-verification', 'deployments']);
const state={authenticated:false,window:'15m',source:'api',search:'',errors:false,build:'',overview:null,request:0,logRequest:0};
const format=new Intl.NumberFormat('ko-KR',{maximumFractionDigits:1});
const clock=value=>new Date(value*1000).toLocaleTimeString('ko-KR',{timeZone:'Asia/Seoul',hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'});
const logDate = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit'});
const set=(id,value)=>$(id).textContent=value;
const finite=value=>typeof value==='number'&&Number.isFinite(value);
const fmt=value=>finite(value)?format.format(value):'—';
const bytes=value=>!finite(value)?'—':value>=1024**3?`${(value/1024**3).toFixed(1)} GB`:`${(value/1024**2).toFixed(0)} MB`;
const jobs={ci:'CI 검사',cd:'운영 CD',verification:'격리 배포·복구'};
const results={SUCCESS:['완료','good'],FAILURE:['실패','bad'],ABORTED:['중단','neutral'],UNSTABLE:['주의','warn'],RUNNING:['진행 중','info'],UNKNOWN_OR_RUNNING:['진행 / 확인 필요','warn'],NOT_BUILT:['미실행','neutral']};

function node(tag,cls,text){const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;}
function notice(message){$('notice').hidden=!message;set('notice',message||'');}
function showLogin(message='기존 Grafana와 같은 아이디·비밀번호를 사용하세요.'){
  state.authenticated=false;state.request++;state.logRequest++;state.overview=null;
  $('workspace').hidden=true;$('login').hidden=false;set('login-message',message);
}
async function api(path,params={}){
  const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),15000);
  try{
    const response=await fetch(API+path+'?'+new URLSearchParams(params),{credentials:'same-origin',cache:'no-store',signal:controller.signal});
    if(response.status===401){showLogin('로그인이 필요해요. Grafana 계정으로 다시 연결해 주세요.');throw new Error('unauthorized');}
    if(!response.ok)throw new Error(response.status===400?'invalid':'unavailable');
    return await response.json();
  }finally{clearTimeout(timeout);}
}
async function connect(){
  set('login-message','기존 Grafana 로그인 상태를 확인하고 있어요.');
  try{
    const data=await api('session');
    state.authenticated=true;$('login').hidden=true;$('workspace').hidden=false;
    set('user-label',data.user.login);await Promise.allSettled([refreshOverview(),refreshLogs()]);
  }catch(error){if(error.message!=='unauthorized')set('login-message','모니터링 연결을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.');}
}
$('login-form').addEventListener('submit',async event=>{
  event.preventDefault();$('login-submit').disabled=true;set('login-message','로그인하고 있어요.');
  try{
    // Credentials go directly to existing Grafana over same-origin HTTPS.
    const response=await fetch('/monitoring/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({user:$('username').value.trim(),password:$('password').value,rememberMe:false})});
    $('password').value='';
    if(!response.ok){set('login-message',response.status===429?'시도가 많아요. 잠시 후 다시 시도해 주세요.':'아이디 또는 비밀번호를 확인해 주세요.');return;}
    await connect();
  }catch{set('login-message','로그인 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.');}
  finally{$('password').value='';$('login-submit').disabled=false;}
});
$('retry-login').addEventListener('click',connect);
$('logout').addEventListener('click',async()=>{
  try{await fetch('/monitoring/logout',{credentials:'same-origin',cache:'no-store'});}finally{location.assign('/status/');}
});

function renderServices(data,forceUnknown=false){
  const fresh=!forceUnknown&&finite(data.collector_age_seconds)&&data.collector_age_seconds<30&&!data.errors.includes('metrics');
  for(const [name,prefix] of [['backend','be'],['ai','ai']]){
    const service=data.services[name]||{};const known=fresh&&typeof service.up==='boolean';
    set(prefix+'-state',known?(service.up?'정상 실행 중':'상태 확인 필요'):'수집 확인 필요');
    const badge=$(prefix+'-badge');badge.className='badge '+(!known?'neutral':service.up?'good':'bad');badge.textContent=!known?'확인 필요':service.up?'정상':'주의';
    set(prefix+'-revision',service.revision?service.revision.slice(0,8):'—');$(prefix+'-revision').title=service.revision||'';
  }
}
function renderChart(points){
  const box=$('traffic-chart');box.replaceChildren();
  const data=points.filter(p=>finite(p.time)&&finite(p.value));
  if(data.length<2){box.append(node('p','empty-chart','그래프에 표시할 요청 샘플을 모으고 있어요.'));set('chart-start','');set('chart-end','');return;}
  const ns='http://www.w3.org/2000/svg';const svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 800 160');svg.setAttribute('preserveAspectRatio','none');svg.setAttribute('role','img');svg.setAttribute('aria-label','실제 API 초당 요청량 추이');
  const high=Math.max(.1,...data.map(p=>p.value));const first=data[0].time;const duration=Math.max(1,data.at(-1).time-first);
  for(const y of [15,55,95,135]){const line=document.createElementNS(ns,'line');for(const [k,v] of Object.entries({x1:0,x2:800,y1:y,y2:y,stroke:'#eaf0f7','stroke-dasharray':'3 5'}))line.setAttribute(k,v);svg.append(line);}
  const coords=data.map(p=>[2+(p.time-first)/duration*796,140-p.value/high*120]);
  const d=coords.map((p,i)=>(i?'L':'M')+p.map(v=>v.toFixed(2)).join(',')).join(' ');
  const fill=document.createElementNS(ns,'path');fill.setAttribute('d',d+` L798,150 L2,150 Z`);fill.setAttribute('fill','#edf4ff');svg.append(fill);
  const line=document.createElementNS(ns,'path');line.setAttribute('d',d);line.setAttribute('fill','none');line.setAttribute('stroke','#6b9ee7');line.setAttribute('stroke-width','2.2');line.setAttribute('vector-effect','non-scaling-stroke');svg.append(line);box.append(svg);
  set('chart-start',clock(first));set('chart-end',clock(data.at(-1).time));
}
function emptyRow(body,count,message){body.replaceChildren();const tr=node('tr');const td=node('td','empty-cell',message);td.colSpan=count;tr.append(td);body.append(tr);}
function renderOverview(data){
  renderServices(data);set('rps',fmt(data.api.rps));set('p95',finite(data.api.p95)?fmt(data.api.p95*1000):'—');set('request-total',fmt(data.api.total));
  const cpu=data.host.cpu_ratio;set('cpu-text',finite(cpu)?`${(cpu*100).toFixed(1)}%`:'—');$('cpu-meter').value=finite(cpu)?Math.max(0,Math.min(1,cpu)):0;
  const used=finite(data.host.memory_total)&&data.host.memory_total>0&&finite(data.host.memory_available)?1-data.host.memory_available/data.host.memory_total:null;
  set('memory-text',finite(used)?`${(used*100).toFixed(1)}%`:'—');$('memory-meter').value=finite(used)?Math.max(0,Math.min(1,used)):0;
  set('memory-detail',finite(used)?`${bytes(data.host.memory_available)} / ${bytes(data.host.memory_total)} 사용 가능`:'메모리 지표 확인 필요');set('disk-text',bytes(data.host.disk_available));
  renderChart(data.trend);const table=$('api-table');table.replaceChildren();set('route-count',data.api.routes.length);
  if(!data.api.routes.length)emptyRow(table,4,data.errors.includes('metrics')?'API 지표 연결을 확인해 주세요.':'아직 수집된 API 요청이 없어요.');
  for(const route of data.api.routes){const tr=node('tr');const name=node('td');const code=node('code','',route.route);name.append(code);tr.append(name,node('td','',fmt(route.total)),node('td',route.client_errors?'number-warn':'',fmt(route.client_errors)),node('td',route.server_errors?'number-bad':'',fmt(route.server_errors)));table.append(tr);}
  const builds=$('build-table');builds.replaceChildren();
  if(!data.builds.length)emptyRow(builds,6,'수집된 빌드 기록이 없어요. 실행되지 않은 CD는 표시되지 않아요.');
  for(const build of data.builds){
    const tr=node('tr');tr.append(node('td','job-name',jobs[build.job]||build.job),node('td','',`#${build.build}`));
    const status=node('td');const [label,color]=results[build.result]||['확인 필요','neutral'];status.append(node('span','badge '+color,label));tr.append(status);
    tr.append(node('td','',build.timestamp?new Date(build.timestamp*1000).toLocaleString('ko-KR',{timeZone:'Asia/Seoul',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}):'—'));
    const duration=build.duration_seconds;tr.append(node('td','',finite(duration)?duration>=60?`${Math.floor(duration/60)}분 ${Math.round(duration%60)}초`:`${Math.round(duration)}초`:'—'));
    const action=node('td');const button=node('button','table-link','로그 보기');button.addEventListener('click',()=>{state.source=build.job==='verification'?'jenkins-verification':'jenkins-'+build.job;state.build=String(build.build);state.search='';$('log-source').value=state.source;$('log-search').value='';$('errors-only').checked=false;state.errors=false;refreshLogs();$('log-section').scrollIntoView({behavior:'smooth'});});action.append(button);tr.append(action);builds.append(tr);
  }
  const stale=!finite(data.collector_age_seconds)||data.collector_age_seconds>=30;
  notice(data.errors.length?'일부 지표를 가져오지 못했어요. 정상 상태로 판단하지 말고 Grafana에서 수집 연결을 확인해 주세요.':stale?'수집이 지연되고 있어요. 표시된 값은 마지막 관측값이며 현재 정상 여부를 확인해야 해요.':'');
  set('updated-at',`마지막 조회 ${clock(data.generated_at)}`);
}
async function refreshOverview(){
  if(!state.authenticated)return;const request=++state.request;$('refresh').disabled=true;
  try{const data=await api('overview',{window:state.window});if(request!==state.request)return;state.overview=data;renderOverview(data);}
  catch(error){if(request!==state.request||error.message==='unauthorized')return;notice('지표 서버에 연결하지 못했어요. 마지막 표시값을 현재 정상 상태로 판단하지 마세요.');if(state.overview)renderServices(state.overview,true);}
  finally{if(request===state.request)$('refresh').disabled=false;}
}
function renderLogs(data){
  const box=$('log-output');box.replaceChildren();set('log-count',`${data.rows.length}줄 표시 · ${clock(data.generated_at)} 조회`);
  if(!data.rows.length){box.append(node('p','log-empty','선택한 기간과 조건에 맞는 로그가 없어요.'));return;}
  for(const row of data.rows){
    const error=/error|exception|failure|failed|-> 5\d\d/i.test(row.message);const warn=/warn|-> 4\d\d/i.test(row.message);
    const line=node('div','log-row'+(error?' error':warn?' warning':''));
    const milliseconds = Number(BigInt(row.time_ns) / 1000000n);
    const timestamp = node('time', 'log-time');
    if (deploymentLogSources.has(row.source)) {
      timestamp.append(logDate.format(milliseconds), document.createElement('br'));
    }
    timestamp.append(clock(milliseconds / 1000));
    line.append(timestamp);
    const message=node('div');const text=node('pre','log-message',row.message.length>800?row.message.slice(0,800)+'…':row.message);message.append(text);
    if(row.message.length>800){const button=node('button','log-expand','전체 보기');button.addEventListener('click',()=>{const expanded=button.textContent==='접기';text.textContent=expanded?row.message.slice(0,800)+'…':row.message;button.textContent=expanded?'전체 보기':'접기';});message.append(button);}
    line.append(message);box.append(line);
  }
}
async function refreshLogs(){
  if(!state.authenticated)return;const request=++state.logRequest;
  const filter=$('build-filter');filter.hidden=!state.build;filter.querySelector('span').textContent=state.build?`빌드 #${state.build}`:'';
  const deploymentWindow = $('deployment-log-window');
  deploymentWindow.hidden = !deploymentLogSources.has(state.source);
  const window = deploymentWindow.hidden ? state.window : deploymentWindow.value;
  try{const data=await api('logs',{window,source:state.source,search:state.search,errors:state.errors?'1':'0',build:state.build});if(request===state.logRequest)renderLogs(data);}
  catch(error){if(request!==state.logRequest||error.message==='unauthorized')return;$('log-output').replaceChildren(node('p','log-empty','로그 서버에 연결하지 못했어요. 기간을 줄이거나 잠시 후 다시 확인해 주세요.'));set('log-count','로그 연결 확인 필요');}
}
$('window').addEventListener('change', () => {
  state.window = $('window').value;
  refreshOverview();
  if (!deploymentLogSources.has(state.source)) refreshLogs();
});
$('deployment-log-window').addEventListener('change', refreshLogs);
$('refresh').addEventListener('click',()=>{refreshOverview();refreshLogs();});
$('log-source').addEventListener('change',()=>{state.source=$('log-source').value;state.build='';refreshLogs();});
$('log-search-form').addEventListener('submit',event=>{event.preventDefault();state.search=$('log-search').value.trim();refreshLogs();});
$('errors-only').addEventListener('change',()=>{state.errors=$('errors-only').checked;refreshLogs();});
$('build-filter').querySelector('button').addEventListener('click',()=>{state.build='';refreshLogs();});
document.querySelectorAll('.nav-link').forEach(link=>link.addEventListener('click',()=>{document.querySelectorAll('.nav-link').forEach(x=>x.classList.remove('active'));link.classList.add('active');}));
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&state.authenticated){refreshOverview();refreshLogs();}});
setInterval(()=>{if(!document.hidden&&state.authenticated){refreshOverview();refreshLogs();}},15000);
connect();
