"""Root/Child execution-scope repair: exactly one new REQ-01 attempt, then STOP."""
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from contract_fix import runner,require_unused
from scope_consistency import check

ROOT=runner.ROOT
CAMPAIGN=ROOT/'target/multi-agent/multi-scopefix-20261003-r1'
REPORT=ROOT/'evals/multi-agent/reports/execution-scope'

def main():
    if len(sys.argv)!=1:raise RuntimeError('No task override or retry')
    require_unused(CAMPAIGN)
    if subprocess.check_output(['git','branch','--show-current'],text=True).strip()!='feat/multi-agent':raise RuntimeError('Branch mismatch')
    if subprocess.check_output(['git','diff','--cached','--name-only'],text=True).strip():raise RuntimeError('Nonempty index')
    if runner.legacy.digest(ROOT/'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java')!='8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d':raise RuntimeError('Protected Handler changed')
    for path,digest in runner.legacy.read(REPORT/'historical-preserved.json').items():
        if runner.legacy.digest(ROOT/path)!=digest:raise RuntimeError('Historical artifact changed: '+path)
    frozen=runner.freeze()
    if runner.legacy.read(REPORT/'model-gate.json')['freeze']!=frozen:raise RuntimeError('Source changed after deterministic gate')
    suites=[ET.parse(p).getroot() for p in (ROOT/'target/surefire-reports').glob('TEST-*-phase6-scope.xml')]
    if len(suites)!=6 or sum(int(s.get('tests')) for s in suites)!=78 or any(int(s.get(k,0)) for s in suites for k in ('failures','errors','skipped')):raise RuntimeError('Focused gate failed')
    subprocess.run(['git','diff','--check'],check=True)
    tasks=[p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['task_id']=='REQ-01']
    if len(tasks)!=1:raise RuntimeError('Ambiguous frozen task')
    with (ROOT/'target/procurement-v2/world.lock').open('a+b') as stream:
        if sys.platform=='win32':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        CAMPAIGN.mkdir(exist_ok=False)
        runner.legacy.write(CAMPAIGN/'freeze.json',frozen)
        runner.legacy.write(CAMPAIGN/'authorization.json',dict(tasks=['REQ-01'],max_new_attempts=1,max_new_valid=1,prior_phase6_valid=5,no_retry=True))
        attempt=CAMPAIGN/'REQ-01-1-a1';print('START REQ-01 one authorized attempt',flush=True)
        row=runner.trial(tasks[0],attempt);row['cohort']='post-execution-scope-fix'
        if (attempt/'raw.json').exists():
            raw=runner.legacy.read(attempt/'raw.json')
            review=check(raw);runner.legacy.write(attempt/'final-state-consistency.json',review)
            row['SupervisorFinalStateConsistency']=review['status']
            row['contract_errors']=[e['result']['metadata']['errorType'] for e in raw['executions'] if (e.get('result') or {}).get('metadata',{}).get('errorType') in {'CHILD_OUTPUT_INVALID','CANONICAL_REFERENCE_MISMATCH','MISSING_CANONICAL_REFERENCE'}]
        runner.legacy.write(attempt/'result.json',row)
        runner.legacy.write(CAMPAIGN/'accounting.json',dict(prior_valid_trials=5,new_attempts=1,new_valid=int(row['status']!='EVAL_INFRA_ERROR'),new_infra=int(row['status']=='EVAL_INFRA_ERROR'),stopped=True))
        runner.legacy.write(CAMPAIGN/'source-unchanged.json',dict(unchanged=runner.freeze()==frozen))
        print(json.dumps({k:row.get(k) for k in ('status','failure_reason','SupervisorFinalStateConsistency','contract_errors','tool_calls','tool_errors','model_calls','total_tokens')},ensure_ascii=False),flush=True)
        print('STOP: inspect prose and archive. PASS/FAIL never authorizes another trial.',flush=True)

if __name__=='__main__':main()
