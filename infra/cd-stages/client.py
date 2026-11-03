"""Agent-side SSH transport and atomic public progress snapshots; no background daemon."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import uuid

from protocol import PHASES, accept, event, request

DIRECTORY = Path('stage-status')


def save(value):
    temporary = DIRECTORY / 'state.tmp'
    temporary.write_text(json.dumps(value), encoding='utf-8')
    os.replace(temporary, DIRECTORY / 'state.json')


def load(): return json.loads((DIRECTORY / 'state.json').read_text(encoding='utf-8'))


def initialize():
    command = ':'.join(['stages', os.environ['YAMU_CD_BUILD'], os.environ['YAMU_CD_SCENARIO'], uuid.uuid4().hex])
    build, scenario, run_id = request(command)
    DIRECTORY.mkdir(mode=0o700)
    save({'request': command, 'run_id': run_id, 'ci_build': build, 'scenario': scenario, 'phases': {}, 'done': None})


def consume(lines, snapshot):
    for sequence, line in enumerate(lines, 1):
        value = event(line, snapshot['run_id'], sequence)
        accept(snapshot, value)
        with (DIRECTORY / 'events.jsonl').open('a', encoding='utf-8') as journal:
            journal.write(json.dumps(value) + '\n')
        save(snapshot)
    if not snapshot.get('done'): raise ValueError('Transport ended without a terminal event')


def run():
    snapshot = load(); request(snapshot['request'])
    key = os.environ['VERIFY_KEY']
    if not Path(key).is_file(): raise ValueError('Jenkins SSH credential file missing')
    args = ['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', 'UserKnownHostsFile=/etc/yamu-cd/known_hosts', '-o', 'ConnectTimeout=15',
            '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2', '-i', key,
            'yamu-deploy@100.102.125.41', snapshot['request']]
    process = None
    def interrupted(number, frame): raise InterruptedError('Jenkins transport interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8')
        # Bounded line reader; rejects non-protocol command output without logging its contents.
        def lines():
            while True:
                line = process.stdout.readline(16385)
                if not line: return
                if len(line) > 16384: raise ValueError('Oversized transport line')
                yield line
        consume(lines(), snapshot)
        if process.wait(timeout=15) != 0 or snapshot['done']['state'] != 'success':
            raise ValueError('Isolated verification did not succeed')
        print('All isolated CD phases and final report verified.')
        return 0
    except BaseException as error:
        snapshot['done'] = {'state': 'failed', 'error_type': type(error).__name__,
                            'host_result': snapshot.get('done')}
        save(snapshot)
        print('Isolated CD transport failed: ' + type(error).__name__)
        return 1
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()


def poll(phase, mode):
    """0=observed condition, 10=pending, 20=failed/missing at terminal state."""
    if not (DIRECTORY / 'state.json').is_file(): return 10
    snapshot = load()
    if phase == 'final':
        if not snapshot.get('done'): return 10
        return 0 if snapshot['done']['state'] == 'success' and all(snapshot['phases'].get(p) == 'success' for p in PHASES) else 20
    if phase not in PHASES + ['recovery']: raise ValueError('Invalid phase name')
    state = snapshot['phases'].get(phase)
    if mode == 'started' and state is not None: return 0
    if mode == 'finished' and state in ['success', 'failed']: return 0 if state == 'success' else 20
    return 20 if snapshot.get('done') else 10


if __name__ == '__main__':
    try:
        action = sys.argv[1]
        if action == 'init': initialize()
        elif action == 'run': sys.exit(run())
        elif action == 'poll': sys.exit(poll(sys.argv[2], sys.argv[3]))
        else: raise ValueError('Unknown client action')
    except Exception as error:
        print('Progress client stopped: ' + type(error).__name__)
        sys.exit(1)
