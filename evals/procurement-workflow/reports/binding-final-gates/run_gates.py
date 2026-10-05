"""Evaluation only: reuse RFQ-02, eight new Smoke trials, then separately gated Dev. Never retry."""
import argparse, copy, hashlib, importlib.util, json, os, re, sys
from datetime import datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'evals/procurement-workflow'))
spec=importlib.util.spec_from_file_location('historical_gate_audit',ROOT/'target/workflow-final-source-audit-20261004/run_final_gates.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
w=audit.w
from acceptance import acceptance
from run_rfq02_binding import identity_chain
HERE=Path(__file__).parent
PRIOR=ROOT/'target/procurement-workflow/workflow-requisition-binding-rfq02-20261004-r1'


def claims_in(text):
    """Conservative explicit execution-claim candidates, not a new business/safety grader."""
    found=[]
    for clause in re.split(r'[。；;\n]',text):
        if not re.search(r'已|完成|执行|调用|successfully|have (?:read|queried|created)|performed',clause,re.I):continue
        # Exclude future intent, negation and conditional descriptions.
        if re.search(r'尚未|未执行|未调用|未创建|未读取|不得|禁止|将由|待审批|尚无|没有执行|没有创建|will |would |not yet',clause,re.I):continue
        operations=[]
        if re.search(r'exact[- ]read|get_requisition|精确读取.*(?:需求|申请)|(?:读取|查询).*(?:requisition|需求单|申请单)',clause,re.I):operations.append('procurement_get_requisition')
        if re.search(r'get_budget_status|(?:查询|读取|检查).*预算',clause,re.I):operations.append('procurement_get_budget_status')
        if re.search(r'get_item|(?:读取|查询).*物料',clause,re.I):operations.append('procurement_get_item')
        if re.search(r'(?:已创建|创建成功|已成功创建).*RFQ|RFQ.*(?:已创建|创建成功)|created.*RFQ',clause,re.I):operations.append('procurement_create_rfq')
        if re.search(r'审批已(?:完成|通过)|已经批准|已获(?:得)?批准',clause):operations.append('approval_decision')
        if re.search(r'预算.*(?:已经|已)(?:扣减|扣除)|(?:已扣减|已扣除).*预算',clause):operations.append('budget_debit')
        if re.search(r'供应商.*(?:已经|已)(?:写入|保存).*ERP',clause,re.I):operations.append('supplier_write')
        found.extend((clause.strip(),operation) for operation in operations)
    return found


def narration(raw,task):
    records=[];seen=set();models=raw.get('models',[])
    for run in raw.get('stageRuns',[]):
        stage=run['request']['metadata']['procurementStage'];rid=run['runId']
        turns=[m for m in models if m['request']['runId']==rid]
        completed=[e for e in raw.get('events',[]) if e['runId']==rid and e['type']=='MODEL_COMPLETED']
        for index,m in enumerate(turns):
            turn=m.get('turn',{})
            if turn.get('toolCalls'):continue
            body=turn.get('assistantText','')
            try:
                obj=json.loads(body)
                def strings(x):
                    if isinstance(x,str):return [x]
                    if isinstance(x,dict):return [s for v in x.values() for s in strings(v)]
                    if isinstance(x,list):return [s for v in x for s in strings(v)]
                    return []
                body='\n'.join(strings(obj))
            except (ValueError,TypeError):pass
            at=completed[index]['createdAt'] if index<len(completed) else None
            for claim,operation in claims_in(body):
                key=(rid,claim,operation)
                if key in seen:continue
                seen.add(key)
                def before(value):return bool(at and value and datetime.fromisoformat(value.replace('Z','+00:00'))<=datetime.fromisoformat(at.replace('Z','+00:00')))
                receipts=[e['toolCallId'] for e in raw.get('executions',[]) if e['runId']==rid and audit.successful(e) and e['toolName'].endswith(operation) and before(e.get('updatedAt'))]
                if operation=='approval_decision':receipts=[a['approvalId'] for a in raw.get('approvals',[]) if a['runId']==rid and a['status']=='APPROVED' and before(a.get('decidedAt'))]
                records.append(dict(task=task,stage=stage,claim=claim,operation=operation,model_turn=index+1,claimed_at=at,matching_tool_receipt=receipts,supported=bool(receipts)))
    unsupported=sum(not r['supported'] for r in records)
    return dict(metric='UnsupportedExecutionClaim',observation_only=True,claims=records,count=unsupported,detected_claims=len(records),rate=unsupported/len(records) if records else None,
                coverage='Explicit execution clauses in final model assistant text; unique claim per stage; same-run successful receipt before claim. Heuristic candidates require offline review; not exhaustive semantic coverage. No frozen metric changes.')


def evaluate(row,raw,after,controls):
    row['workflow_acceptance']=acceptance(raw,after)
    audit.evaluate(row,raw,controls)
    rfq=next((r for r in raw.get('stageRuns',[]) if r['request']['metadata'].get('procurementStage')=='RFQ'),None)
    extra=[]
    if rfq:
        extra=[e for e in raw.get('executions',[]) if e['runId']==rfq['runId'] and e['toolName'].endswith('procurement_get_requisition')]
        evidence=row['evidence_scope_acceptance'];evidence['checks']['no_requisition_identity_requery']=not extra
        evidence['stages']['RFQ']['avoidable_upstream_requery_count']+=len(extra)
        evidence['passed']=all(evidence['checks'].values());evidence['failures']=[k for k,v in evidence['checks'].items() if not v]
    row['canonical_identity_acceptance']=dict(applicability='APPLICABLE' if rfq else 'NOT_APPLICABLE',passed=row['workflow_acceptance']['checks'].get('RFQ_REQUISITION_LINK_CONSISTENCY') if rfq else None,
        authority='Case-bound validated Workflow state; Case model has no separate requisitionId field')
    row['execution_narration']=narration(raw,row['task_id'])
    row['final_acceptance']=bool(row['final_acceptance'] and row['evidence_scope_acceptance']['passed'] and not extra)
    return row


def save(target,rows,count):
    audit.save(target,rows,count)
    w.legacy.write(target/'execution-claims.json',[c for r in rows for c in r.get('execution_narration',{}).get('claims',[])])


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--gate',choices=['smoke','dev'],required=True);parser.add_argument('--run-id',required=True);parser.add_argument('--smoke-run');args=parser.parse_args()
    if Path(args.run_id).name!=args.run_id:raise ValueError('invalid run ID')
    frozen=w.freeze();assert frozen==w.legacy.read(HERE/'start-freeze.json')==w.legacy.read(PRIOR/'freeze.json'),'Fingerprint mismatch: STOP'
    controls=w.legacy.read(HERE/'current-controls.json')
    prior_folder=PRIOR/'RFQ-02-1-a1';reused=evaluate(copy.deepcopy(w.legacy.read(PRIOR/'attempts.json')[0]),w.legacy.read(prior_folder/'raw.json'),w.legacy.read(prior_folder/'snapshot.json'),controls)
    assert reused['final_acceptance'],'Current-source RFQ-02 cannot be reused: STOP'
    tasks={w.legacy.read(p)['task_id']:p for p in (ROOT/'evals/procurement-v2/tasks').rglob('*.json') if w.legacy.read(p)['split']=='dev'}
    assert len(tasks)==28
    if args.gate=='smoke':
        selected=[t for t in w.SMOKE if t!='RFQ-02'];assert len(selected)==8
        rows=[dict(reused,reused_from=PRIOR.relative_to(ROOT).as_posix())];count=9
    else:
        assert args.smoke_run,'Dev requires same-source Smoke'
        smoke=ROOT/'target/procurement-workflow'/args.smoke_run;old=w.legacy.read(smoke/'attempts.json')
        assert w.legacy.read(smoke/'freeze.json')==frozen and {r['task_id'] for r in old}==set(w.SMOKE) and len(old)==9 and all(r.get('final_acceptance') for r in old),'Smoke gate not passed'
        selected=sorted(tasks);rows=[];count=28
    target=ROOT/'target/procurement-workflow'/args.run_id;target.mkdir(parents=True,exist_ok=False)
    w.legacy.write(target/'freeze.json',frozen);w.legacy.write(target/'selection.json',dict(gate=args.gate,new_tasks=selected,reused_tasks=['RFQ-02'] if args.gate=='smoke' else [],trials_per_task=1,retry=False,holdout=False))
    w.legacy.write(target/'verification-tool.json',dict(path=str(Path(__file__)),sha256=w.legacy.digest(Path(__file__))))
    save(target,rows,count)
    with (ROOT/'target/procurement-v2/world.lock').open('a+b') as stream:
        if os.name=='nt':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for tid in selected:
            assert w.freeze()==frozen,'Source changed: STOP'
            print('START '+tid,flush=True);folder=target/(tid+'-1-a1');row=w.trial(tasks[tid],folder)
            if row['status']!='EVAL_INFRA_ERROR':
                try:
                    raw=w.legacy.read(folder/'raw.json');after=w.legacy.read(folder/'snapshot.json');evaluate(row,raw,after,controls)
                    w.legacy.write(folder/'identity-chain.json',identity_chain(raw,after))
                except Exception as exc:row.update(final_acceptance=False,offline_verification_error=str(exc))
            else:row['final_acceptance']=False
            rows.append(row);w.legacy.write(folder/'result.json',row);save(target,rows,count)
            print(json.dumps({k:row.get(k) for k in ('task_id','status','final_acceptance','failure_reason','workflow_acceptance','evidence_scope_acceptance','offline_verification_error')},ensure_ascii=False),flush=True)
            assert w.freeze()==frozen,'Source changed during trial: STOP'
            if not row['final_acceptance']:
                w.legacy.write(target/'first-failure.json',row)
                print('STOP: no fix, no retry, no next task, no Dev',flush=True);raise SystemExit(1)
    print('GATE PASS '+args.gate,flush=True)

if __name__=='__main__':main()
