"""Public, bounded progress protocol for one isolated CD execution."""
import json
import re

PHASES = ['validate', 'prepare', 'baseline', 'deploy', 'health', 'persist',
          'manual_rollback', 'automatic_rollback', 'persistence', 'final_deploy', 'final_health']
RESULT_FLAGS = ['successful_deploy', 'manual_rollback', 'automatic_rollback_healthy',
                'db_marker_preserved', 'redis_marker_preserved', 'final_candidate_healthy', 'production_unchanged']


def request(value):
    match = re.fullmatch(r'stages:([1-9][0-9]{0,8}):(cached|ci):(normal|fail-health):([0-9a-f]{32})', value)
    if not match: raise ValueError('Invalid isolated verification request')
    return int(match[1]), match[2], match[3], match[4]


def event(line, run_id, sequence):
    if len(line) > 16384: raise ValueError('Oversized progress event')
    value = json.loads(line)
    if value.get('run_id') != run_id or value.get('seq') != sequence:
        raise ValueError('Progress identity or sequence differs')
    if value.get('kind') == 'phase':
        if value.get('phase') not in PHASES + ['recovery'] or value.get('state') not in ['running', 'success', 'failed']:
            raise ValueError('Invalid progress phase')
    elif value.get('kind') == 'done':
        if value.get('state') not in ['success', 'failed']: raise ValueError('Invalid final state')
        if value['state'] == 'success':
            result = value.get('result', {})
            if result.get('project') != 'yamu-cd-verify-20261029' or result.get('rds_used') is not False \
                    or result.get('artifact_source') not in ['cached_fixture', 'retained_ci'] \
                    or any(result.get(k) is not True for k in RESULT_FLAGS) \
                    or not re.fullmatch('[0-9a-f]{40}', result.get('ci_revision', '')) \
                    or type(result.get('ci_build')) is not int or result['ci_build'] <= 0:
                raise ValueError('Missing final verification evidence')
    else: raise ValueError('Unknown progress event')
    return value


def accept(snapshot, value):
    if snapshot.get('done'): raise ValueError('Events after completion')
    if value['kind'] == 'phase':
        phase, state = value['phase'], value['state']
        previous = snapshot['phases'].get(phase)
        if state == 'running':
            if previous is not None: raise ValueError('Duplicate phase')
            if any(s == 'running' for s in snapshot['phases'].values()): raise ValueError('Overlapping phase')
            if phase != 'recovery':
                index = PHASES.index(phase)
                if index and snapshot['phases'].get(PHASES[index - 1]) != 'success':
                    raise ValueError('Out of order phase')
        elif previous != 'running': raise ValueError('Phase completion without start')
        snapshot['phases'][phase] = state
    else:
        if any(s == 'running' for s in snapshot['phases'].values()): raise ValueError('Unfinished phase')
        if value['state'] == 'success' and (any(snapshot['phases'].get(p) != 'success' for p in PHASES)
                or any(s != 'success' for s in snapshot['phases'].values())):
            raise ValueError('Success with missing phases')
        snapshot['done'] = value
