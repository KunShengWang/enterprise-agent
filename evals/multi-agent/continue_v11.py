"""Authorized continuation: reuse two D1 artifacts offline, at most 7 new Smoke + 28 Dev.

One model trial per invocation; explicit offline review before progression. No retry.
"""
import argparse
import json
import subprocess
import sys
from acceptance_d import runner, verify as verify_prior
from acceptance_v11 import evaluate, VERSION

ROOT = runner.ROOT
REPORT = ROOT / 'evals/multi-agent/reports/acceptance-v1.1'
CAMPAIGN = ROOT / 'target/multi-agent/multi-v11-20261003-r1'


def rows(mode):
    paths = list((CAMPAIGN / mode).glob('*/result.json'))
    if mode == 'smoke':
        paths += list((CAMPAIGN / 'offline').glob('*/regrade.json'))
    return [runner.legacy.read(p) for p in sorted(paths)]


def require_pass(values, expected):
    if len(values) != len(expected) or {r['task_id'] for r in values} != set(expected):
        raise RuntimeError('Incomplete/duplicate gate')
    for row in values:
        if any(row.get(k) != 'PASS' for k in ('status', 'acceptance_status', 'SupervisorFinalStateConsistency')) or row.get('acceptance_failures') or row.get('invariant_violations'):
            raise RuntimeError('Failed/unreviewed task; STOP')
        if row.get('acceptance_version') != VERSION:
            raise RuntimeError('Wrong acceptance version')
        review = runner.legacy.read(ROOT / row.get('review_directory', row['artifact_directory']) / 'offline-review.json')
        if review.get('verdict') != 'PASS':
            raise RuntimeError('Explicit offline review required')


def verify():
    frozen = verify_prior()
    prior = runner.legacy.read(REPORT / 'pre-freeze.json')
    for key, value in prior.items():
        if key == 'harness_runner':
            if any(frozen[key].get(k) != v for k, v in value.items()):
                raise RuntimeError('Previous evaluator/runner changed')
        elif frozen[key] != value:
            raise RuntimeError('Frozen control/source changed: ' + key)
    for path, digest in runner.legacy.read(REPORT / 'historical-preserved.json').items():
        if runner.legacy.digest(ROOT / path) != digest:
            raise RuntimeError('Historical artifact changed: ' + path)
    return frozen


def controls(raw):
    runs = {r['runId']: r for r in raw['runs']}
    by_role = {}
    for model in raw['models']:
        req = model['request']; role = runs[req['runId']]['executionProfile']['name']
        entry = by_role.setdefault(role, dict(prompts=set(), tools={}))
        entry['prompts'].add(model['effectivePromptSha256'])
        for tool in req['tools']:
            value = runner.legacy.schema_hash([tool])
            if tool['name'] in entry['tools'] and entry['tools'][tool['name']] != value:
                raise RuntimeError('Tool definition varied within role')
            entry['tools'][tool['name']] = value
    return dict(root_prompt=raw['controls']['profilePromptSha256'], model_options=raw['controls']['effectiveModelOptions'],
                roles={k: dict(prompts=sorted(v['prompts']), tools=v['tools']) for k, v in by_role.items()})


def compare_controls(current, known):
    if current['root_prompt'] != known['root_prompt'] or current['model_options'] != known['model_options']:
        raise RuntimeError('Prompt/model control mismatch')
    for role, value in current['roles'].items():
        if role not in known['roles']:
            continue
        old = known['roles'][role]
        if value['prompts'] != old['prompts']:
            raise RuntimeError('Effective role prompt mismatch: ' + role)
        for name, digest in value['tools'].items():
            if name in old['tools'] and digest != old['tools'][name]:
                raise RuntimeError('Role schema mismatch: ' + name)


def main(mode):
    frozen = verify()
    if frozen != runner.legacy.read(REPORT / 'model-gate.json')['freeze']:
        raise RuntimeError('Evaluator changed after deterministic gate')
    if runner.legacy.read(CAMPAIGN / 'freeze.json') != frozen:
        raise RuntimeError('Campaign source differs')
    tasks = {runner.legacy.read(p)['task_id']: p for p in (ROOT / 'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['split'] == 'dev'}
    if len(tasks) != 28:
        raise RuntimeError('Wrong Dev set')
    if mode == 'dev':
        require_pass(rows('smoke'), runner.SMOKE)
    for row in rows('smoke') + rows('dev'):
        require_pass([row], [row['task_id']])
    selected = runner.SMOKE if mode == 'smoke' else sorted(tasks)
    completed = {r['task_id'] for r in rows(mode)}
    pending = [tid for tid in selected if tid not in completed]
    if not pending:
        print('Gate complete'); return
    tid = pending[0]
    if mode == 'smoke' and tid in {'REQ-01', 'REQ-03'}:
        raise RuntimeError('Existing tasks must never be rerun')
    with (ROOT / 'target/procurement-v2/world.lock').open('a+b') as stream:
        if sys.platform == 'win32':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        attempt = CAMPAIGN / mode / (tid + '-1-a1')
        if attempt.exists():
            raise RuntimeError('Attempt reserved; no retry')
        print('START ' + mode + ' ' + tid, flush=True)
        row = runner.trial(tasks[tid], attempt)
        if (attempt / 'raw.json').exists():
            raw = runner.legacy.read(attempt / 'raw.json')
            row, consistency, evidence = evaluate(runner.legacy.read(tasks[tid]), row, raw)
            actual_controls = controls(raw)
            try:
                compare_controls(actual_controls, runner.legacy.read(CAMPAIGN / 'controls-reference.json'))
                controls_status = 'PASS'
            except RuntimeError as exc:
                controls_status = 'FAIL'
                row['acceptance_failures'].append(str(exc)); row['acceptance_status'] = 'FAIL'
            runner.legacy.write(attempt / 'controls-check.json', dict(status=controls_status, actual=actual_controls, production_fingerprint=frozen['production']['sha256'], frozen_source_seed_task_match=True))
            runner.legacy.write(attempt / 'final-state-consistency.json', consistency)
            runner.legacy.write(attempt / 'acceptance-evidence.json', evidence)
        else:
            row.update(acceptance_version=VERSION, acceptance_status='FAIL', acceptance_failures=['missing raw evidence'], SupervisorFinalStateConsistency='UNVERIFIED')
        row.update(cohort='final-source-acceptance-v1.1', new_model_trial=True)
        runner.legacy.write(attempt / 'result.json', row)
        all_new = [r for r in rows('smoke') + rows('dev') if r.get('new_model_trial')]
        runner.legacy.write(CAMPAIGN / 'accounting.json', dict(prior_debug_valid=6, reused_final_source_trials=2,
            new_valid=sum(r['status'] != 'EVAL_INFRA_ERROR' for r in all_new), new_infra=sum(r['status'] == 'EVAL_INFRA_ERROR' for r in all_new),
            max_new_valid=35, smoke_new=sum(r.get('new_model_trial', False) for r in rows('smoke')), dev_new=len(rows('dev'))))
        if verify() != frozen:
            raise RuntimeError('Source changed during trial')
        print(json.dumps({k: row.get(k) for k in ('task_id', 'status', 'failure_reason', 'acceptance_status', 'acceptance_failures', 'SupervisorFinalStateConsistency', 'tool_calls', 'total_tokens')}, ensure_ascii=False), flush=True)
        print('STOP until explicit offline review; no retry.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['smoke', 'dev'], required=True)
    main(parser.parse_args().mode)
