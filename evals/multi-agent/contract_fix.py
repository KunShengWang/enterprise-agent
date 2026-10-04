"""Second contract repair: exactly one REQ-03 attempt, never other tasks or automatic retry."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('contract_phase6_runner',Path(__file__).with_name('run.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
ROOT=runner.ROOT
CAMPAIGN=ROOT/'target/multi-agent/multi-contractfix-20261003-r1'
REPORT=ROOT/'evals/multi-agent/reports/contract-fix'

def require_unused(folder):
    if folder.exists():raise RuntimeError('One-attempt contract-fix budget already reserved; STOP, no retry')

def validate_focused(paths):
    suites=[ET.parse(p).getroot() for p in paths]
    if len(suites)!=5 or any(int(s.get(k,'0')) for s in suites for k in ('failures','errors','skipped')):
        raise RuntimeError('Focused test gate incomplete or failed')
    if sum(int(s.get('tests','0')) for s in suites)!=67:raise RuntimeError('Focused test count differs')
    return [dict(s.attrib) for s in suites]

def main():
    if len(sys.argv)!=1:raise RuntimeError('No task override, resume, or retry supported')
    require_unused(CAMPAIGN)
    if subprocess.check_output(['git','branch','--show-current'],cwd=ROOT,text=True).strip()!='feat/multi-agent':raise RuntimeError('Branch mismatch')
    if subprocess.check_output(['git','diff','--cached','--name-only'],cwd=ROOT,text=True).strip():raise RuntimeError('Index must remain empty')
    protected=ROOT/'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java'
    if runner.legacy.digest(protected)!='8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d':raise RuntimeError('Protected Handler changed')
    subprocess.run(['git','diff','--check'],cwd=ROOT,check=True)
    proof=runner.legacy.read(REPORT/'pre-contract-preserved.json')
    for path,digest in proof.items():
        if runner.legacy.digest(ROOT/path)!=digest:raise RuntimeError('Historical evidence changed')
    suites=validate_focused(sorted((ROOT/'target/surefire-reports').glob('TEST-*-phase6-contract.xml')))
    frozen=runner.freeze()
    if runner.legacy.read(REPORT/'model-gate.json')['freeze']!=frozen:raise RuntimeError('Source differs from deterministic gate')
    tasks=[p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['task_id']=='REQ-03']
    if len(tasks)!=1:raise RuntimeError('Frozen task identity ambiguous')
    lock=ROOT/'target/procurement-v2/world.lock'
    with lock.open('a+b') as stream:
        if sys.platform=='win32':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        CAMPAIGN.mkdir(exist_ok=False)
        runner.legacy.write(CAMPAIGN/'freeze.json',frozen)
        runner.legacy.write(CAMPAIGN/'authorization.json',dict(tasks=['REQ-03'],trials=1,automatic_retry=False,focused=suites))
        row=runner.trial(tasks[0],CAMPAIGN/'REQ-03-1-a1')
        row['cohort']='post-contract-fix'
        runner.legacy.write(CAMPAIGN/'REQ-03-1-a1/result.json',row)
        runner.legacy.write(CAMPAIGN/'accounting.json',dict(prior_phase6_valid=3,new_attempts=1,new_valid=int(row['status']!='EVAL_INFRA_ERROR'),new_infra=int(row['status']=='EVAL_INFRA_ERROR'),stopped=True))
        runner.legacy.write(CAMPAIGN/'source-unchanged.json',dict(unchanged=runner.freeze()==frozen))
        print(json.dumps({k:row.get(k) for k in ('task_id','status','failure_reason','invariant_violations','tool_calls','model_calls','total_tokens','stop_reason')},ensure_ascii=False),flush=True)
        print('STOP for human Review. PASS and FAIL both consume this authorization.',flush=True)

if __name__=='__main__':main()
