"""Final-source C1/C2 acceptance. One next task per invocation; offline review gates progression.

Reuses the unmodified trial driver, frozen tasks, model controls and V2.2 grader.
No retries. Historical development results never enter this acceptance cohort.
"""
import argparse
import collections
import json
from pathlib import Path
import subprocess
import sys
from contract_fix import runner

ROOT=runner.ROOT
CAMPAIGN=ROOT/'target/multi-agent/multi-final-20261003-r1'
REPORT=ROOT/'evals/multi-agent/reports/final-acceptance'
SOURCE='aeaf76ea3e9e3da50ee5a4a370fa0f9c4772f918586f86553758651f6bcd6f35'
ERRORS={'CHILD_OUTPUT_INVALID','CANONICAL_REFERENCE_MISMATCH','MISSING_CANONICAL_REFERENCE'}

def require_pass(rows,expected):
    if len(rows)!=len(expected) or {r['task_id'] for r in rows}!=set(expected):
        raise RuntimeError('Gate incomplete or duplicate task')
    if any(r['status']!='PASS' or r.get('invariant_violations') or r.get('acceptance_failures') for r in rows):
        raise RuntimeError('Gate failed; STOP')

def audit(row,raw):
    failures=[]
    metrics=dict(row.get('multi_agent_metrics',{}))
    if row['status']!='PASS':failures.append('frozen grader '+row['status'])
    if row.get('invariant_violations'):failures.append('safety invariant violation')
    failures.extend('routing: '+e for e in metrics.get('routing_errors',[]))
    records={e['toolCallId']:e for e in raw.get('executions',[])}
    runs={r['runId']:r for r in raw.get('runs',[])}
    root=raw.get('result',{}).get('runId')
    for e in records.values():
        result=e.get('result') or {}
        code=result.get('metadata',{}).get('errorType')
        if code in ERRORS:failures.append(code+': '+e['toolCallId'])
        if e['toolName'] not in ('analyze_requisition','source_suppliers','prepare_rfq') or not result.get('success'):continue
        body=json.loads(result['content']);child=runs[body['childRunId']]
        packet=json.loads(child['request']['question']);allowed={x['evidenceRef']:x for x in body['evidenceLedger']}
        for ref in body['analysis']['evidenceRefs']:
            entry=allowed.get(ref)
            if entry is None:failures.append('missing ledger evidence '+ref);continue
            source=records.get(entry['sourceToolCallId'])
            if not source or source['state']!='SUCCEEDED' or not (source.get('result') or {}).get('success'):
                failures.append('unsuccessful positive evidence '+ref)
            elif source['runId']!=child['runId'] and ref not in packet.get('priorEvidenceRefs',[]):
                failures.append('private cross-child evidence '+ref)
        facts=body['executionFacts']
        receipts=facts['casePatchReceipts']
        if facts['casePatched']!=bool(receipts):failures.append('inconsistent committed Patch flag')
        for receipt in receipts:
            patch=records.get(receipt['sourceToolCallId'])
            case=raw.get('case') or {}
            if not patch or patch['state']!='SUCCEEDED' or not patch['result']['success'] or patch['toolName']!='procurement_case_patch' or receipt['sourceToolCallId'] not in case.get('appliedInputIds',[]):
                failures.append('Patch claim without authoritative receipt')
    for role in ('root','child'):
        models=[m for m in raw.get('models',[]) if (m['request']['runId']==root)==(role=='root')]
        metrics[role+'_input_tokens']=sum(m.get('turn',{}).get('usage',{}).get('promptTokens',0) for m in models)
        metrics[role+'_output_tokens']=sum(m.get('turn',{}).get('usage',{}).get('completionTokens',0) for m in models)
    metrics['total_provider_input_tokens']=row.get('input_tokens')
    metrics['unnecessary_subagent_calls']=None
    metrics['unnecessary_limitation']='Only frozen forbidden routing is counted; ambiguous optional calls are not subjectively classified.'
    row['final_source_metrics']=metrics
    row['acceptance_failures']=sorted(set(failures))
    return row

def rows(mode):return [runner.legacy.read(p) for p in sorted((CAMPAIGN/mode).glob('*/result.json'))]

def main(mode):
    current=runner.freeze()
    approved=runner.legacy.read(ROOT/'evals/multi-agent/reports/contract-fix/freeze.json')
    for name,value in approved.items():
        if name!='harness_runner' and current[name]!=value:raise RuntimeError('Frozen source/controls changed: '+name)
    for name,value in approved['harness_runner'].items():
        if current['harness_runner'].get(name)!=value:raise RuntimeError('Existing runner changed: '+name)
    if current['production']['sha256']!=SOURCE:raise RuntimeError('Production changed')
    if subprocess.check_output(['git','branch','--show-current'],text=True).strip()!='feat/multi-agent':raise RuntimeError('Wrong branch')
    if subprocess.check_output(['git','diff','--cached','--name-only'],text=True).strip():raise RuntimeError('Index not empty')
    if runner.legacy.digest(ROOT/'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java')!='8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d':raise RuntimeError('Handler changed')
    for path,digest in runner.legacy.read(REPORT/'historical-preserved.json').items():
        if runner.legacy.digest(ROOT/path)!=digest:raise RuntimeError('Historical evidence changed')
    tasks={runner.legacy.read(p)['task_id']:p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['split']=='dev'}
    if len(tasks)!=28:raise RuntimeError('Task selection changed')
    selected=runner.SMOKE if mode=='smoke' else sorted(tasks)
    if mode=='dev':require_pass(rows('smoke'),runner.SMOKE)
    allrows=rows('smoke')+rows('dev')
    for row in allrows:
        require_pass([row],[row['task_id']])
        review=Path(ROOT/row['artifact_directory'])/'offline-review.json'
        if not review.exists() or runner.legacy.read(review).get('verdict')!='PASS':raise RuntimeError('Offline behavioral review required')
    existing=rows(mode)
    if len(existing)>=len(selected):print('Gate complete');return
    tid=selected[len(existing)]
    if any(r['task_id']==tid for r in existing):raise RuntimeError('Duplicate attempt')
    with (ROOT/'target/procurement-v2/world.lock').open('a+b') as stream:
        if sys.platform=='win32':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        CAMPAIGN.mkdir(exist_ok=True)
        freeze=CAMPAIGN/'freeze.json'
        if freeze.exists() and runner.legacy.read(freeze)!=current:raise RuntimeError('Source changed within cohort')
        if not freeze.exists():runner.legacy.write(freeze,current)
        runner.legacy.write(CAMPAIGN/'authorization.json',dict(smoke=runner.SMOKE,dev=sorted(tasks),max_valid_trials=37,debug_history_valid=4,no_retry=True))
        attempt=CAMPAIGN/mode/(tid+'-1-a1')
        if attempt.exists():raise RuntimeError('Attempt already reserved; no retry')
        print('START '+mode+' '+tid,flush=True)
        row=runner.trial(tasks[tid],attempt)
        raw=runner.legacy.read(attempt/'raw.json') if (attempt/'raw.json').exists() else {}
        row=audit(row,raw);row.update(cohort='final-source-acceptance',acceptance_gate='C1' if mode=='smoke' else 'C2')
        runner.legacy.write(attempt/'result.json',row)
        runner.legacy.write(CAMPAIGN/mode/'attempts.json',rows(mode))
        runner.legacy.write(CAMPAIGN/mode/'summary.json',runner.summarize(rows(mode),len(selected),1))
        allrows=rows('smoke')+rows('dev')
        runner.legacy.write(CAMPAIGN/'accounting.json',dict(debug_history_valid=4,new_valid=sum(r['status']!='EVAL_INFRA_ERROR' for r in allrows),new_infra=sum(r['status']=='EVAL_INFRA_ERROR' for r in allrows),max_new_valid=37,smoke_attempts=len(rows('smoke')),dev_attempts=len(rows('dev'))))
        if runner.freeze()!=current:raise RuntimeError('Source changed during trial')
        print(json.dumps({k:row.get(k) for k in ('task_id','status','failure_reason','acceptance_failures','tool_calls','tool_errors','total_tokens','stop_reason')},ensure_ascii=False),flush=True)
        require_pass([row],[tid])
        print('Await offline behavioral review before next task.',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['smoke','dev'],required=True)
    main(parser.parse_args().mode)
