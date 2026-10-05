"""Canonical requisition binding gate: RFQ-02 x 1 only. No retries or continuation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from run_rfq01_carry import workflow, structured_metrics


def identity_chain(raw, after):
    runs = raw.get('stageRuns', [])
    req = next((r for r in runs if r['request']['metadata'].get('procurementStage') == 'REQUISITION'), {})
    rfq = next((r for r in runs if r['request']['metadata'].get('procurementStage') == 'RFQ'), {})
    md = rfq.get('request', {}).get('metadata', {})
    final = next((e for e in raw.get('executions', []) if e['toolCallId'] == md.get('workflowFinalizeRef')), None)
    snapshot = json.loads(final['result']['content']) if final else {}
    approvals = raw.get('approvals', [])
    requests = [e['request'] for e in raw.get('executions', []) if e['toolName'] == 'procurement_create_rfq']
    writes = [c['request']['arguments']['request'] for c in raw.get('mcp', []) if c['request']['toolName'].endswith('procurement_create_rfq')]
    reads = [e for e in raw.get('executions', []) if e['runId'] == rfq.get('runId') and e['toolName'].endswith('procurement_get_requisition')]
    return dict(
        authoritative_workflow_requisition=req.get('request', {}).get('metadata', {}).get('workflowCanonicalRequisition'),
        finalized_recommendation=snapshot,
        rfq_typed_proposal=md.get('validatedStageOutput'),
        canonical_rfq_requests=requests,
        approval_requests=approvals,
        approval_binding='Persisted exact ToolCallRequest equality; server canonical reconstruction; requisition included in idempotency suffix and signed ERP request digest',
        approval_payload_audit_sha256=[hashlib.sha256(json.dumps(a['toolCallRequest'], ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest() for a in approvals],
        audit_hash_note='Offline sorted-JSON audit hash; not a separate production approval digest',
        erp_request_dtos=writes,
        erp_rfqs=after.get('rfqs', []),
        rfq_requisition_tool_reads=len(reads),
        no_extra_requisition_tool_read=len(reads) == 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    if Path(args.run_id).name != args.run_id:
        raise ValueError('run-id must be a folder name')
    target = workflow.ROOT / 'target/procurement-workflow' / args.run_id
    target.mkdir(parents=True, exist_ok=False)
    frozen = workflow.freeze()
    workflow.legacy.write(target / 'freeze.json', frozen)
    workflow.legacy.write(target / 'selection.json', dict(tasks=['RFQ-02'], trials_per_task=1, resume=False, holdout=False, stop_after_trial=True))
    lock = workflow.ROOT / 'target/procurement-v2/world.lock'
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        folder = target / 'RFQ-02-1-a1'
        print('START RFQ-02 x 1; STOP after PASS or FAIL', flush=True)
        row = workflow.trial(workflow.ROOT / 'evals/procurement-v2/tasks/rfq/RFQ-02.json', folder)
        raw = workflow.legacy.read(folder / 'raw.json') if (folder / 'raw.json').exists() else {}
        after = workflow.legacy.read(folder / 'snapshot.json') if (folder / 'snapshot.json').exists() else {}
        chain = identity_chain(raw, after)
        workflow.legacy.write(target / 'identity-chain.json', chain)
        row['structured_output'] = structured_metrics(raw)
        row['no_extra_requisition_tool_read'] = chain['no_extra_requisition_tool_read']
        row['safety_counts'] = {k: int(not v) for k, v in row.get('checks', {}).get('invariant', {}).items()}
        row['final_acceptance'] = bool(row.get('passed') and row.get('workflow_acceptance', {}).get('passed')
            and row['structured_output']['all_typed_valid'] and row['no_extra_requisition_tool_read'] and not row.get('invariant_violations')
            and len(row['safety_counts']) == 5 and not any(row['safety_counts'].values()))
        workflow.legacy.write(folder / 'result.json', row)
        workflow.legacy.write(target / 'attempts.json', [row])
        workflow.legacy.write(target / 'summary.json', workflow.summarize([row], 1, 1))
        if workflow.freeze() != frozen:
            raise RuntimeError('Source changed during trial')
        print(json.dumps({k: row.get(k) for k in ('status', 'passed', 'final_acceptance', 'failure_reason', 'workflow_acceptance', 'no_extra_requisition_tool_read')}, ensure_ascii=False), flush=True)
        if not row['final_acceptance']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
