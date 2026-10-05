"""Single Agent Workflow: frozen Smoke/Dev only, stop on any failed gate."""
import argparse
import collections
import gzip
import json
import os
from pathlib import Path
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'evals/procurement-v2/runners'))
import run as legacy
from reporting import enrich_attempt, summarize
sys.path.insert(0, str(ROOT/'evals/procurement-v2.2'))
from grade_v22 import grade

import importlib.util
from acceptance import acceptance, metrics as workflow_metrics
spec=importlib.util.spec_from_file_location('harness_world_runner',ROOT/'evals/harness-core/run.py')
harness=importlib.util.module_from_spec(spec);spec.loader.exec_module(harness)

SMOKE = ['REQ-01','REQ-03','ANOM-01','SRC-01','RFQ-01','RFQ-02','GOV-01','ROB-01','E2E-01']
FATAL = {'UnauthorizedWrite','DuplicateSideEffect','StaleApprovalExecution'}

world=harness.world

def runtime_metrics(raw):
    events=raw.get('events',[])
    tools=[e['payload'] for e in events if e['type']=='TOOL_COMPLETED']
    waits=[e['payload'] for e in events if e['type']=='APPROVAL_REQUIRED']
    result=raw['result'];reason=result['stopReason']
    return dict(outcome_counts=dict(collections.Counter(t.get('outcome','UNCLASSIFIED') for t in tools+waits)),
                tool_latency_ms=[t.get('durationMs',0) for t in tools],
                retry_count=sum(t.get('retryCount',0) for t in tools),turn_count=result['budget']['turns'],
                tool_attempt_count=sum(t.get('toolAttempts',1) for t in tools),
                tool_attempt_error_count=sum(t.get('toolErrors',int(not t.get('success',False))) for t in tools),
                recovered_retryable_errors=sum(t.get('recoveredRetryableErrors',0) for t in tools),
                tool_call_count=result['budget']['toolCalls'],stop_reason=reason,
                budget_exhausted=reason in ('MAX_TURNS','TOOL_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT'),
                run_duration_ms=raw.get('taskLatencyMs'),
                states=[e['payload']['runtimeState'] for e in events if 'runtimeState' in e['payload']])

def trial(taskpath, folder):
    folder.mkdir(parents=True,exist_ok=False);task=legacy.read(taskpath);raw={};started=time.monotonic()
    try:
        before=world(taskpath,folder,'reset')
        classpath=str(ROOT/'target/test-classes')+os.pathsep+str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/procurement-v2-classpath.txt').read_text().strip()
        quote=lambda p:'"'+str(p).replace('\\','/')+'"'
        args=folder/'java.args'
        args.write_text('-cp\n'+quote(classpath)+'\ncom.agent.platform.procurement.ProcurementWorkflowTrialDriver\n'+quote(taskpath)+'\n'+quote(folder/'raw.json')+'\n',encoding='utf-8')
        env=legacy.model_env();temp=ROOT/'target/procurement-v2/jvm-temp';temp.mkdir(exist_ok=True,parents=True)
        env['TEMP']=env['TMP']=str(temp)
        legacy.command(['java','@'+str(args)],folder/'agent.log',timeout=900,env=env)
        raw=legacy.read(folder/'raw.json');after=world(taskpath,folder,'snapshot')
        row=grade(task,raw,before,after)
        row['runtime_metrics']=runtime_metrics(raw)
        row['workflow_acceptance']=acceptance(raw,after)
        row['workflow_metrics']=workflow_metrics(raw)
        row['turns']=sum(r['budgetSnapshot']['turns'] for r in raw['stageRuns'])
        row['runtime_metrics']['turn_count']=row['turns']
        row['runtime_metrics']['tool_call_count']=sum(r['budgetSnapshot']['toolCalls'] for r in raw['stageRuns'])
        row['controls']=dict(profile_prompt_sha256=raw['controls']['profilePromptSha256'],
                             schema_sha256=sorted({legacy.schema_hash(m['request']['tools']) for m in raw['models']}),
                             model_options=raw['controls']['effectiveModelOptions'],
                             max_tool_execution_attempts=raw['controls']['maxToolExecutionAttempts'])
        with gzip.open(folder/'artifact.json.gz','wt',encoding='utf-8') as stream:
            json.dump(dict(task=task,raw=raw,before=before,after=after),stream,ensure_ascii=False,separators=(',',':'))
        row['artifact_sha256']=legacy.digest(folder/'artifact.json.gz')
    except Exception as exc:
        row=dict(task_id=task['task_id'],split=task['split'],status='EVAL_INFRA_ERROR',passed=None,
                 failure_category='EVAL_INFRA_ERROR',failure_reason=[str(exc)],invariant_violations=[])
    row.update(trial_id=task['task_id']+'-1',artifact_directory=folder.relative_to(ROOT).as_posix(),wall_clock_seconds=time.monotonic()-started)
    enrich_attempt(row,raw,folder);legacy.write(folder/'result.json',row);return row

def freeze():
    value=legacy.manifest()
    value['workflow_driver']=legacy.digest(ROOT/'src/test/java/com/agent/platform/procurement/ProcurementWorkflowTrialDriver.java')
    value['workflow_runner']={p.name:legacy.digest(p) for p in Path(__file__).parent.glob('*.py')}
    value['v22']={p.name:legacy.digest(p) for p in (ROOT/'evals/procurement-v2.2').glob('*.py')}
    return value

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['smoke','dev'],required=True)
    parser.add_argument('--run-id',required=True);parser.add_argument('--smoke-run');parser.add_argument('--resume',action='store_true')
    args=parser.parse_args()
    tasks={legacy.read(p)['task_id']:p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if legacy.read(p)['split']=='dev'}
    assert len(tasks)==28 and set(SMOKE)<=set(tasks)
    selected=SMOKE if args.mode=='smoke' else sorted(tasks)
    frozen=freeze()
    if args.mode=='dev':
        if not args.smoke_run: raise RuntimeError('Dev requires a passed Smoke run')
        smoke=ROOT/'target/procurement-workflow'/args.smoke_run
        if legacy.read(smoke/'freeze.json')!=frozen: raise RuntimeError('Smoke source differs')
        summary=legacy.read(smoke/'summary.json')
        if not all(r.get('workflow_acceptance',{}).get('passed') for r in legacy.read(smoke/'attempts.json')): raise RuntimeError('Workflow Smoke must pass before Dev')
        if summary['passed']!=len(SMOKE) or summary['valid_trials']!=len(SMOKE): raise RuntimeError('Smoke must pass before Dev')
    target=ROOT/'target/procurement-workflow'/args.run_id;target.mkdir(parents=True,exist_ok=True)
    if (target/'freeze.json').exists():
        if not args.resume or legacy.read(target/'freeze.json')!=frozen: raise RuntimeError('Use new run ID for changed source; --resume only for same-source infra recovery')
    else: legacy.write(target/'freeze.json',frozen)
    legacy.write(target/'selection.json',dict(mode=args.mode,tasks=selected,trials_per_task=1,holdout=False))
    lock=ROOT/'target/procurement-v2/world.lock';lock.parent.mkdir(exist_ok=True,parents=True)
    with lock.open('a+b') as stream:
        if os.name=='nt':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        rows=[]
        for tid in selected:
            existing=[legacy.read(p) for p in sorted(target.glob(tid+'-1-a*/result.json'))];rows.extend(existing)
            if existing and existing[-1]['status']!='EVAL_INFRA_ERROR':
                if not existing[-1].get('passed') or not existing[-1].get('workflow_acceptance',{}).get('passed'): raise RuntimeError('Prior Smoke failure: STOP; no rerun')
                continue
            folder=target/f'{tid}-1-a{len(existing)+1}';print('START '+folder.name,flush=True)
            row=trial(tasks[tid],folder);rows.append(row)
            legacy.write(target/'summary.json',summarize(rows,len(selected),1));legacy.write(target/'attempts.json',rows)
            print(json.dumps({k:row.get(k) for k in ('trial_id','status','failure_reason','tool_calls')},ensure_ascii=False),flush=True)
            if row['status']!='EVAL_INFRA_ERROR' and (not row.get('passed') or not row.get('workflow_acceptance',{}).get('passed')): raise RuntimeError('Business/workflow failure: STOP; Dev forbidden')
            if FATAL.intersection(row.get('invariant_violations',[])): raise RuntimeError('Safety invariant violation: STOP')
            if row['status']=='EVAL_INFRA_ERROR': raise RuntimeError('Infrastructure failure: STOP; diagnose before explicit resume')
        if freeze()!=frozen: raise RuntimeError('Source changed during run')

if __name__=='__main__':main()
