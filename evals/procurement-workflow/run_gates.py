"""Final-source Workflow acceptance: reuse REQ-01; W1 remaining Smoke, then gated Dev."""
import argparse
import json
import os
from pathlib import Path
import run_req01

w = run_req01.workflow
ROOT = w.ROOT
PRIOR = ROOT / 'target/procurement-workflow/workflow-structured-req01-20261004-r1'


def canonical_profile(profile):
    value = dict(profile)
    value['toolScope'] = sorted(value['toolScope'])
    value['outputSchema'] = json.loads(value['outputSchema'])
    return value


def validate_controls(raw, controls):
    profiles = {p['stage']: p for p in controls['stageProfiles']}
    checks = {
        'model_configuration': raw['controls']['effectiveModelOptions'] == controls['effectiveModelOptions'],
        'runtime_configuration': raw['controls']['agentProperties'] == controls['agentProperties'],
        'stage_profiles': all(canonical_profile(p) == canonical_profile(profiles[p['stage']]) for p in raw['controls']['stageProfiles']),
    }
    for run in raw['stageRuns']:
        stage = run['request']['metadata']['procurementStage']
        p = profiles[stage]
        prompt = p['instructions'] + '\n最终只输出符合以下 JSON Schema 的 JSON 对象，不使用 markdown fence：\n' + p['outputSchema']
        models = [m for m in raw['models'] if m['request']['runId'] == run['runId']]
        checks[stage + '_prompt'] = all(m['request']['systemPrompt'] == prompt for m in models)
        checks[stage + '_tools'] = all(w.legacy.schema_hash(m['request']['tools']) == w.legacy.schema_hash(controls['toolsByStage'][stage]) for m in models)
    return dict(passed=all(checks.values()), checks=checks)


def evaluate(row, raw, controls):
    row['structured_output'] = run_req01.structured_metrics(raw)
    row['configuration_acceptance'] = validate_controls(raw, controls)
    stages = row['structured_output']['stages'].values()
    row['final_acceptance'] = bool(row.get('passed') and row.get('workflow_acceptance', {}).get('passed')
        and row['configuration_acceptance']['passed'] and row['structured_output']['all_typed_valid']
        and all(s['structured_output_repair_count'] <= 1 and s['structured_output_failure_count'] == 0 for s in stages)
        and not row.get('invariant_violations'))
    return row


def save(target, rows, count):
    w.legacy.write(target / 'attempts.json', rows)
    summary = w.summarize(rows, count, 1)
    summary['workflow_acceptance_passed'] = sum(bool(r.get('workflow_acceptance', {}).get('passed')) for r in rows)
    summary['final_acceptance_passed'] = sum(bool(r.get('final_acceptance')) for r in rows)
    w.legacy.write(target / 'summary.json', summary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--gate', choices=['w1', 'w2'], required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--smoke-run')
    args = parser.parse_args()
    if Path(args.run_id).name != args.run_id:
        raise ValueError('run-id must be one directory name')
    controls = w.legacy.read(ROOT / 'target/workflow-w1-audit/current-controls.json')
    prior_freeze = w.legacy.read(PRIOR / 'freeze.json')
    frozen = w.freeze()
    # A new orchestration entrypoint is permitted; every prior source/runner remains immutable.
    checks = {k: v == frozen.get(k) for k, v in prior_freeze.items() if k != 'workflow_runner'}
    checks.update({'runner:' + k: v == frozen['workflow_runner'].get(k) for k, v in prior_freeze['workflow_runner'].items()})
    if not all(checks.values()):
        raise RuntimeError('Prior source/config/task/seed/grader/runner fingerprints differ: ' + str(checks))
    reused = w.legacy.read(PRIOR / 'attempts.json')[0]
    raw = w.legacy.read(PRIOR / 'REQ-01-1-a1/raw.json')
    evaluate(reused, raw, controls)
    if not reused['final_acceptance']:
        raise RuntimeError('Existing REQ-01 cannot be reused')
    tasks = {w.legacy.read(p)['task_id']: p for p in (ROOT / 'evals/procurement-v2/tasks').rglob('*.json') if w.legacy.read(p)['split'] == 'dev'}
    assert len(tasks) == 28
    if args.gate == 'w1':
        selected = [t for t in w.SMOKE if t != 'REQ-01']
        assert len(selected) == 8
        rows = [dict(reused, reused_from=PRIOR.relative_to(ROOT).as_posix())]
        count = 9
    else:
        if not args.smoke_run:
            raise RuntimeError('W2 requires passed W1')
        smoke = ROOT / 'target/procurement-workflow' / args.smoke_run
        old_rows = w.legacy.read(smoke / 'attempts.json')
        if w.legacy.read(smoke / 'freeze.json') != frozen or len(old_rows) != 9 or not all(r.get('final_acceptance') for r in old_rows):
            raise RuntimeError('W1 not completely passed on identical source')
        selected = sorted(tasks)
        rows = []
        count = 28
    target = ROOT / 'target/procurement-workflow' / args.run_id
    target.mkdir(parents=True, exist_ok=False)
    w.legacy.write(target / 'freeze.json', frozen)
    w.legacy.write(target / 'reuse-verification.json', dict(fingerprints=checks, controls=reused['configuration_acceptance'], prior_artifact_sha256=reused['artifact_sha256']))
    w.legacy.write(target / 'selection.json', dict(gate=args.gate, new_tasks=selected, reused_tasks=['REQ-01'] if args.gate == 'w1' else [], trials_per_task=1, holdout=False))
    save(target, rows, count)
    with (ROOT / 'target/procurement-v2/world.lock').open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for task_id in selected:
            if w.freeze() != frozen:
                raise RuntimeError('Source changed: stop gate')
            folder = target / (task_id + '-1-a1')
            print('START ' + task_id, flush=True)
            row = w.trial(tasks[task_id], folder)
            if row['status'] != 'EVAL_INFRA_ERROR':
                evaluate(row, w.legacy.read(folder / 'raw.json'), controls)
            else:
                row['final_acceptance'] = False
            rows.append(row)
            w.legacy.write(folder / 'result.json', row)
            save(target, rows, count)
            print(json.dumps({k: row.get(k) for k in ('task_id', 'status', 'final_acceptance', 'failure_reason', 'workflow_acceptance')}, ensure_ascii=False), flush=True)
            if w.freeze() != frozen:
                raise RuntimeError('Source changed during trial: stop gate')
            if not row['final_acceptance']:
                print('STOP: failed gate; no rerun, no next task, no Dev', flush=True)
                raise SystemExit(1)


if __name__ == '__main__':
    main()
