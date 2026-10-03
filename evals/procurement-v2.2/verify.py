"""Verify the uncommitted Harness B0 record, inputs, protocol and Git boundaries."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path[:0]=[str(ROOT/'evals/procurement-v2/runners'),str(ROOT/'evals/procurement-v2/graders')]
from reporting import summarize

def read(p): return json.loads(p.read_text(encoding='utf-8'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT,text=True,encoding='utf-8')

def main():
    baseline=read(HERE/'harness-b0.json'); initial=read(HERE/'reports/audit-start.json')
    assert all(sha(ROOT/p)==h for p,h in initial['files'].items()), 'pre-existing content changed'
    assert git('rev-parse','HEAD').strip()==initial['head']
    assert git('branch','--show-current').strip()==initial['branch']
    assert git('show-ref','--tags')==initial['tags']
    assert not git('diff','--cached','--name-only').strip()
    for p,h in baseline['freeze_files'].items(): assert sha(ROOT/p)==h, p
    assert baseline['implementation_commit'] is None and baseline['production']['working_tree_dirty']
    summary=read(HERE/'reports/summary.json')
    rows=[json.loads(line) for line in (HERE/'reports/trials.jsonl').read_text(encoding='utf-8').splitlines()]
    for k,v in summarize(rows,40,3).items(): assert summary[k]==v,k
    assert baseline['metrics']==summary['metrics']
    assert baseline['invariant_counts']==summary['invariant_counts']
    assert baseline['success']==dict(passed=summary['passed'],failed=summary['failed'],valid_trials=summary['valid_trials'],
        consistent_tasks=summary['three_of_three_consistent_success_count'],task_count=summary['task_count'])
    assert summary['passed']==120 and summary['failed']==0 and summary['three_of_three_consistent_success_count']==40
    assert not any(summary['invariant_counts'].values())
    legacy=read(HERE/'reports/legacy-regression.json')
    assert all(r['exitCode']==0 and not r['failures'] and not r['errors'] for r in legacy['phases'])
    assert all(sha(ROOT/f['path'])==f['sha256'] for f in legacy['fixtures'])
    report=dict(passed=True,baseline='Harness B0',protocol='procurement-eval-v2.2',
        baseline_record_sha256=sha(HERE/'harness-b0.json'),freeze_files_verified=len(baseline['freeze_files']),
        preexisting_files_unchanged=len(initial['files']),head=initial['head'],branch=initial['branch'],
        index_empty=True,tags_unchanged=True,source_artifacts_unchanged=True,production_unchanged=True,
        protected_handler_unchanged=True,legacy_fingerprints_unchanged=5,uncommitted=True)
    (HERE/'reports/final-verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main()
