"""Fixed-source host collector. No network listener, Docker socket export or secrets.

Only normalized nginx metadata and redacted, allowlisted application/build logs
are published. Private cursors/counters are separate from the read-only spool.
At-least-once logs: a crash between spool append and cursor commit can duplicate.
"""
import datetime as dt
import glob
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

CONFIG = Path('/etc/yamu-monitoring/config.json')
BUCKETS = (.005, .01, .025, .05, .1, .25, .5, 1, 2, 5, 10, 30, 60)
METHODS = {'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'}
JOBS = {'ci': 'ci', 'cd': 'cd', 'verification': 'cd-verification'}
SERVICES = {'backend': 'yamu-prod-backend-1', 'ai': 'yamu-prod-ai-1'}
MAX_READ = 512 * 1024
MAX_LINE = 32768


def run(args):
    return subprocess.check_output(args, stderr=subprocess.PIPE, timeout=15).decode()


def atomic(path, text, mode=0o600):
    temporary = path.with_suffix(path.suffix + '.pending')
    with open(temporary, 'w', encoding='utf-8', opener=lambda p, f: os.open(p, f, mode)) as file:
        file.write(text)
    os.chmod(temporary, mode)
    os.replace(temporary, path)


def redact(message):
    message = re.sub(r'-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----', '[PRIVATE KEY OMITTED]', message, flags=re.S)
    message = re.sub(r'\x1b\[8m.*?\x1b\[0m', '', message, flags=re.S)
    message = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', message)
    if re.search(r'(?i)-----BEGIN .*PRIVATE KEY-----', message):
        return '[PRIVATE KEY LINE OMITTED]'
    message = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', '[JWT REDACTED]', message)
    message = re.sub(r'(?i)\b(Bearer|Basic)\s+[^\s,;"\']+', r'\1 [REDACTED]', message)
    keys = r'YAMU_[A-Z0-9_]+|SSAFY_API_KEY|AWS_[A-Z0-9_]+|authorization|x-pos-key|x-admin-key|private-token|password|passwd|(?:client[_-]?)?secret|token|access[_-]?token|refresh[_-]?token|api[_-]?key|user[_-]?key|pin|card[_-]?no|account[_-]?(?:number|no)|barcode[_-]?token'
    message = re.sub(r'(?i)(\b(?:'+keys+r')\b["\']?\s*[:=]\s*)(["\'])(.*?)\2', r'\1"[REDACTED]"', message, flags=re.S)
    message = re.sub(r'(?i)(\b(?:' + keys + r')\b["\']?\s*[:=]\s*)["\']?[^\s,;"\'}]+', r'\1[REDACTED]', message)
    message = re.sub(r'(?<!\w)(?:\d[ -]?){11,18}\d(?!\w)', '[NUMBER REDACTED]', message)
    message = re.sub(r'\b01[016789][- ]?\d{3,4}[- ]?\d{4}\b', '[PHONE REDACTED]', message)
    message = re.sub(r'\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b', '[EMAIL REDACTED]', message)
    return message[:MAX_LINE]


def tail(path, cursor, initial_tail=64*1024):
    """Read bounded complete lines, including an uncompressed rotated inode."""
    path = Path(path)
    if not path.exists():
        return [], cursor, 0
    current = path.stat()
    files = []
    gaps = 0
    if cursor and cursor.get('inode') != current.st_ino:
        candidates = [Path(p) for p in glob.glob(str(path) + '.*') if not p.endswith('.gz')][:8]
        previous = next((p for p in candidates if p.stat().st_ino == cursor.get('inode')), None)
        if previous:
            files.append((previous, cursor.get('offset', 0)))
        else:
            gaps = 1
        files.append((path, 0))
    else:
        offset = cursor.get('offset', 0) if cursor else max(0, current.st_size-initial_tail)
        if offset > current.st_size:
            gaps += 1
            offset = 0
        files.append((path, offset))
    lines = []
    budget = MAX_READ
    result = cursor
    for source, offset in files:
        with source.open('rb') as file:
            file.seek(offset)
            if not cursor and offset:
                file.readline(MAX_LINE+1)  # Skip possible partial initial line.
            while budget > 0:
                start = file.tell()
                line = file.readline(min(MAX_LINE+1, budget))
                if not line:
                    break
                budget -= len(line)
                if not line.endswith(b'\n'):
                    if len(line) < MAX_LINE+1:
                        file.seek(start)  # Writer may still be appending this line.
                        break
                    gaps += 1
                    while budget > 0:
                        remainder = file.readline(min(MAX_LINE+1, budget))
                        budget -= len(remainder)
                        if not remainder or remainder.endswith(b'\n'):
                            break
                    continue
                lines.append(line.decode('utf-8', errors='replace').rstrip('\r\n'))
            result = {'inode': source.stat().st_ino, 'offset': file.tell()}
        if budget <= 0:
            break
    return lines, result, gaps


def request_sample(line, routes):
    try:
        data = json.loads(line)
        route = data['route'] if data['route'] in routes else '/api/other'
        method = data['method'] if data['method'] in METHODS else 'OTHER'
        status = int(data['status'])
        duration = float(data['duration'])
        if not 100 <= status <= 599 or not math.isfinite(duration) or not 0 <= duration <= 3600:
            return None
        return route, method, status, duration
    except (ValueError, KeyError, TypeError):
        return None


def observe(counters, sample):
    route, method, status, duration = sample
    key = json.dumps([route, method, status], separators=(',', ':'))
    if key not in counters and len(counters) >= 4096:
        return False
    item = counters.setdefault(key, {'count': 0, 'sum': 0, 'buckets': [0]*len(BUCKETS)})
    item['count'] += 1
    item['sum'] += duration
    for index, bound in enumerate(BUCKETS):
        item['buckets'][index] += int(duration <= bound)
    return True


def api_metrics(counters):
    lines = ['# TYPE yamu_api_requests_total counter', '# TYPE yamu_api_request_duration_seconds histogram']
    for key, item in sorted(counters.items()):
        route, method, status = json.loads(key)
        labels = 'route='+json.dumps(route)+',method='+json.dumps(method)+',status='+json.dumps(str(status))
        lines.append(f'yamu_api_requests_total{{{labels}}} {item["count"]}')
        lines.append(f'yamu_api_request_duration_seconds_count{{{labels}}} {item["count"]}')
        lines.append(f'yamu_api_request_duration_seconds_sum{{{labels}}} {item["sum"]}')
        for bound, value in zip(BUCKETS, item['buckets']):
            lines.append(f'yamu_api_request_duration_seconds_bucket{{{labels},le="{bound}"}} {value}')
        lines.append(f'yamu_api_request_duration_seconds_bucket{{{labels},le="+Inf"}} {item["count"]}')
    return lines


def parse_build(path):
    root = ET.parse(path).getroot()
    result = root.findtext('result') or 'RUNNING'
    if result not in {'SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE', 'NOT_BUILT', 'RUNNING'}:
        result = 'UNKNOWN'
    return {'result': result, 'duration_seconds': int(root.findtext('duration') or '0')/1000,
            'timestamp_seconds': int(root.findtext('timestamp') or '0')/1000}


class Collector:
    def __init__(self, config):
        self.config = config
        self.root = Path(config['root'])
        self.public = self.root/'public'
        self.private = self.root/'private'
        for directory in (self.root, self.public, self.public/'metrics', self.public/'logs'):
            directory.mkdir(mode=0o755, parents=True, exist_ok=True)
            directory.chmod(0o755)
        self.private.mkdir(mode=0o700, exist_ok=True)
        self.private.chmod(0o700)
        # Alloy static file targets must exist even before the first request/build.
        for name in ('api','backend','ai','jenkins-ci','jenkins-cd','jenkins-verification','deployments'):
            path=self.public/'logs'/(name+'.jsonl')
            path.touch(exist_ok=True)
            path.chmod(0o644)
        self.statefile = self.private/'state.json'
        self.state = json.loads(self.statefile.read_text()) if self.statefile.exists() else {}
        self.state.setdefault('cursors', {})
        self.state.setdefault('api', {})
        self.state.setdefault('services', {})
        self.state.setdefault('log_gaps', 0)
        self.routes = set(json.loads(Path(config['routes_file']).read_text())) | {'/api/other'}

    def emit(self, service, message, **fields):
        path = self.public/'logs'/(service+'.jsonl')
        if path.exists() and path.stat().st_size >= 10*1024*1024:
            for i in (2, 1):
                old = path.with_suffix(path.suffix+f'.{i}')
                if old.exists():
                    os.replace(old, path.with_suffix(path.suffix+f'.{i+1}'))
            os.replace(path, path.with_suffix(path.suffix+'.1'))
        row = {'observed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
               'service': service, 'message': redact(message), **fields}
        with path.open('a', encoding='utf-8') as file:
            file.write(json.dumps(row, ensure_ascii=False)+'\n')
        path.chmod(0o644)

    def read(self, path, key):
        lines, cursor, gaps = tail(path, self.state['cursors'].get(key))
        if cursor:
            self.state['cursors'][key] = cursor
        self.state['log_gaps'] += gaps
        return lines

    def services(self):
        metrics = []
        template = '{"id":{{json .Id}},"image":{{json .Config.Image}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}},"state":{{json .State.Status}},"started_at":{{json .State.StartedAt}},"log_path":{{json .LogPath}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}"unknown"{{end}}}'
        for service, name in SERVICES.items():
            try:
                info = json.loads(run(['docker', 'inspect', '--format', template, name]))
            except (subprocess.SubprocessError, OSError, ValueError):
                metrics.append(f'yamu_service_up{{service="{service}"}} 0')
                continue
            try:
                up = int(info['state'] == 'running' and info['health'] == 'healthy')
                revision = info['revision'] or info['image'].split(':')[-1]
                metrics.append(f'yamu_service_up{{service="{service}"}} {up}')
                metrics.append('yamu_service_info{service='+json.dumps(service)+',revision='+json.dumps(revision)+'} 1')
                previous = self.state['services'].get(service)
                signature = {key: info[key] for key in ('id', 'image', 'health', 'started_at')}
                if previous != signature:
                    self.emit('deployments', 'Initial observed state' if previous is None else 'Container/image/health changed',
                              component=service, revision=revision, health=info['health'], started_at=info['started_at'])
                    self.state['services'][service] = signature
                if info['log_path']:
                    for line in self.read(info['log_path'], 'docker-'+service):
                        try:
                            item = json.loads(line)
                            self.emit(service, item.get('log', ''), source_time=item.get('time'))
                        except (ValueError, TypeError):
                            self.state['log_gaps'] += 1
            except (subprocess.SubprocessError, OSError, ValueError):
                # Log rotation/read errors must not emit a duplicate/up=0 series
                # after a successful health observation and poison the scrape.
                self.state['log_gaps'] += 1
        return metrics

    def jenkins(self):
        metrics = []
        home = Path(self.config['jenkins_home'])
        for label, job in JOBS.items():
            directory = home/'jobs/yamu/jobs'/job/'builds'
            if not directory.exists():
                continue
            builds = sorted((p for p in directory.iterdir() if p.name.isdigit()), key=lambda p: int(p.name), reverse=True)[:3]
            for build in builds:
                try:
                    info = parse_build(build/'build.xml')
                    number = int(build.name)
                    metrics.append(f'yamu_jenkins_build_result{{job="{label}",build="{number}",result="{info["result"]}"}} 1')
                    metrics.append(f'yamu_jenkins_build_duration_seconds{{job="{label}",build="{number}"}} {info["duration_seconds"]}')
                    metrics.append(f'yamu_jenkins_build_timestamp_seconds{{job="{label}",build="{number}"}} {info["timestamp_seconds"]}')
                except (OSError, ET.ParseError, ValueError):
                    # Running Pipeline often has no build.xml yet. Do not infer SUCCESS.
                    number = int(build.name)
                    metrics.append(f'yamu_jenkins_build_result{{job="{label}",build="{number}",result="UNKNOWN_OR_RUNNING"}} 1')
                for line in self.read(build/'log', 'jenkins-'+label+'-'+build.name):
                    self.emit('jenkins-'+label, line, build=number, job=job)
        return metrics

    def cycle(self):
        metrics = []
        for line in self.read(Path(self.config['nginx_log']), 'nginx-api'):
            sample = request_sample(line, self.routes)
            if sample:
                if observe(self.state['api'], sample):
                    route, method, status, duration = sample
                    self.emit('api', f'{method} {route} -> {status} ({duration}s)', route=route, method=method, status=status, duration=duration)
        metrics += api_metrics(self.state['api'])
        metrics += self.services()
        metrics += self.jenkins()
        mem = {line.split(':')[0]: int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:', 'MemAvailable:'))}
        metrics += [f'yamu_host_memory_total_bytes {mem["MemTotal"]}', f'yamu_host_memory_available_bytes {mem["MemAvailable"]}', f'yamu_host_disk_available_bytes {shutil.disk_usage("/").free}']
        cpu = [int(value) for value in Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]
        old = self.state.get('cpu')
        if old and sum(cpu) > sum(old):
            total = sum(cpu)-sum(old)
            idle = (cpu[3]+cpu[4])-(old[3]+old[4])
            metrics.append(f'yamu_host_cpu_busy_ratio {max(0,min(1,1-idle/total))}')
        self.state['cpu'] = cpu
        metrics += [f'yamu_collector_last_success_timestamp_seconds {time.time()}', f'yamu_collector_log_gaps_total {self.state["log_gaps"]}']
        atomic(self.public/'metrics/yamu.prom', '\n'.join(metrics)+'\n', 0o644)
        # Reap stale build cursors to bound private state; inode keys for services stay stable.
        if len(self.state['cursors']) > 100:
            active = {key: value for key, value in self.state['cursors'].items() if not key.startswith('jenkins-')}
            active.update(list((k,v) for k,v in self.state['cursors'].items() if k.startswith('jenkins-'))[-50:])
            self.state['cursors'] = active
        atomic(self.statefile, json.dumps(self.state))


def main():
    import fcntl
    os.umask(0o077)
    lock = open('/run/lock/yamu-monitoring.lock', 'w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    collector = Collector(json.loads(CONFIG.read_text()))
    while True:
        started = time.monotonic()
        try:
            collector.cycle()
        except Exception as error:
            # Never print command output, file contents or configuration.
            print('Monitoring cycle failed: '+type(error).__name__, flush=True)
        time.sleep(max(1, 5-(time.monotonic()-started)))


if __name__ == '__main__':
    main()
