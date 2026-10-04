"""Explicitly authorized post-fix cohort: A=REQ-01, B=remaining Smoke, C=Dev.

The pre-fix valid failure remains immutable and outside all post-fix metrics.
No prompt, task, grader, model option or production-code changes here.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

spec=importlib.util.spec_from_file_location('phase6_runner',Path(__file__).with_name('run.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
ROOT=runner.ROOT
CAMPAIGN=ROOT/'target/multi-agent/multi-postfix-20261003-r1'
PRE=ROOT/'target/multi-agent/multi-smoke-20261003-r1'
APPROVED_SOURCE='fc6df7aaafc01f9f92ec40b0279fcf33cb98e223c536ebc8a247db1a4f14d2e4'


def require_pass(rows,task_ids):
    valid=[r for r in rows if r['status']!='EVAL_INFRA_ERROR']
    if len(valid)!=len(task_ids) or {r['task_id'] for r in valid}!=set(task_ids):
        raise RuntimeError('Prerequisite gate is incomplete or has duplicate trials')
    if any(r['status']!='PASS' or r.get('invariant_violations') for r in valid):
        raise RuntimeError('Prerequisite gate failed: STOP')


def all_rows(folder):
    return [runner.legacy.read(p) for p in sorted(folder.glob('*/result.json'))]


def preserved_files():
    files=[p for p in PRE.rglob('*') if p.is_file()]
    files += [p for p in (ROOT/'evals/multi-agent/reports').glob('*') if p.is_file()]
    return {p.relative_to(ROOT).as_posix():runner.legacy.digest(p) for p in files}


def run_gate(gate,resume=False):
    frozen=runner.freeze()
    if frozen['production']['sha256']!=APPROVED_SOURCE:
        raise RuntimeError('Production source differs from reviewed post-fix source')
    old=all_rows(PRE)
    if len(old)!=1 or old[0]['task_id']!='REQ-01' or old[0]['status']!='FAIL':
        raise RuntimeError('Expected immutable pre-fix valid failure is missing')
    CAMPAIGN.mkdir(parents=True,exist_ok=True)
    proof=CAMPAIGN/'pre-fix-preserved.json'
    if proof.exists():
        for path,digest in runner.legacy.read(proof).items():
            if runner.legacy.digest(ROOT/path)!=digest:raise RuntimeError('Pre-fix evidence changed')
    else:runner.legacy.write(proof,preserved_files())
    for other in (ROOT/'target/multi-agent').iterdir():
        if other in (PRE,CAMPAIGN):continue
        if any(runner.legacy.read(p)['status']!='EVAL_INFRA_ERROR' for p in other.rglob('result.json')):
            raise RuntimeError('Unexpected valid trials outside authorized cohort')
    snapshot=CAMPAIGN/'freeze.json'
    if snapshot.exists() and runner.legacy.read(snapshot)!=frozen:raise RuntimeError('Source changed between gates')
    if not snapshot.exists():runner.legacy.write(snapshot,frozen)
    smoke=CAMPAIGN/'smoke';dev=CAMPAIGN/'dev'
    if gate=='B':require_pass(all_rows(smoke),['REQ-01'])
    if gate=='C':require_pass(all_rows(smoke),runner.SMOKE)
    tasks={runner.legacy.read(p)['task_id']:p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['split']=='dev'}
    if len(tasks)!=28:raise RuntimeError('Frozen Dev selection changed')
    selected=['REQ-01'] if gate=='A' else runner.SMOKE[1:] if gate=='B' else sorted(tasks)
    folder=dev if gate=='C' else smoke;folder.mkdir(exist_ok=True)
    runner.legacy.write(CAMPAIGN/f'gate-{gate}-selection.json',dict(gate=gate,tasks=selected,trials_per_task=1,cohort='post-fix',pre_fix_valid_trials=1,max_post_fix_valid_trials=37))
    lock=ROOT/'target/procurement-v2/world.lock'
    with lock.open('a+b') as stream:
        if sys.platform=='win32':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for tid in selected:
            rows=all_rows(folder)
            if any(r['status']=='FAIL' or r.get('invariant_violations') for r in rows):raise RuntimeError('Existing valid failure: STOP, no rerun')
            existing=[r for r in rows if r['task_id']==tid]
            if any(r['status']!='EVAL_INFRA_ERROR' for r in existing):continue
            if existing and not resume:raise RuntimeError('Infra recovery requires explicit --resume')
            post=all_rows(smoke)+all_rows(dev)
            if sum(r['status']!='EVAL_INFRA_ERROR' for r in post)>=37:raise RuntimeError('Post-fix trial limit reached')
            if runner.freeze()!=frozen:raise RuntimeError('Source changed before trial')
            attempt=folder/f'{tid}-1-a{len(existing)+1}'
            if attempt.exists():raise RuntimeError('Incomplete attempt exists; audit before recovery')
            print(f'GATE {gate} START {tid}',flush=True)
            row=runner.trial(tasks[tid],attempt)
            row.update(cohort='post-fix',validation_gate=gate)
            runner.legacy.write(attempt/'result.json',row)
            current=all_rows(folder)
            runner.legacy.write(folder/'attempts.json',current)
            runner.legacy.write(folder/'summary.json',runner.summarize(current,28 if gate=='C' else 9,1))
            post=all_rows(smoke)+all_rows(dev)
            runner.legacy.write(CAMPAIGN/'accounting.json',dict(pre_fix_valid=1,post_fix_valid=sum(r['status']!='EVAL_INFRA_ERROR' for r in post),
                post_fix_infra=sum(r['status']=='EVAL_INFRA_ERROR' for r in post),max_post_fix_valid=37,max_phase6_valid=38))
            print(json.dumps({k:row.get(k) for k in ('trial_id','status','failure_reason','invariant_violations','tool_calls','total_tokens')},ensure_ascii=False),flush=True)
            if row['status']!='PASS' or row.get('invariant_violations'):raise RuntimeError('Gate failure or infrastructure interruption: STOP')
            if runner.freeze()!=frozen:raise RuntimeError('Source changed during trial')
        require_pass([r for r in all_rows(folder) if r['task_id'] in selected],selected)
        runner.legacy.write(CAMPAIGN/f'gate-{gate}-passed.json',dict(gate=gate,passed=True,tasks=selected,production_sha256=frozen['production']['sha256']))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--gate',choices=['A','B','C'],required=True);parser.add_argument('--resume',action='store_true')
    args=parser.parse_args();run_gate(args.gate,args.resume)
