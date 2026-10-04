"""D1/D2: one fresh trial per invocation, mandatory offline review, no retries.

Evaluation orchestration only; frozen tasks, driver, controls and business grader reused.
"""
import argparse
import json
import subprocess
import sys
from contract_fix import runner
from scope_consistency import check

ROOT = runner.ROOT
REPORT = ROOT / 'evals/multi-agent/reports/final-d'
CAMPAIGN = ROOT / 'target/multi-agent/multi-final-d-20261003-r1'
AGENT_TOOLS = {'analyze_requisition', 'source_suppliers', 'prepare_rfq'}
ERRORS = {'CHILD_OUTPUT_INVALID', 'CANONICAL_REFERENCE_MISMATCH', 'MISSING_CANONICAL_REFERENCE'}


def rows(mode):
    return [runner.legacy.read(p) for p in sorted((CAMPAIGN / mode).glob('*/result.json'))]


def require_pass(values, expected):
    if len(values) != len(expected) or {r['task_id'] for r in values} != set(expected):
        raise RuntimeError('Incomplete/duplicate gate')
    for r in values:
        if any(r.get(k) != 'PASS' for k in ('status', 'acceptance_status', 'SupervisorFinalStateConsistency')) or r.get('invariant_violations') or r.get('acceptance_failures'):
            raise RuntimeError('Failed or unreviewed task; STOP')
        review = runner.legacy.read(ROOT / r['artifact_directory'] / 'offline-review.json')
        if review.get('verdict') != 'PASS':
            raise RuntimeError('Offline evidence/prose review required')


def verify():
    frozen = runner.freeze()
    prior = runner.legacy.read(REPORT / 'pre-freeze.json')
    for key, value in prior.items():
        if key == 'harness_runner':
            if any(frozen[key].get(k) != v for k, v in value.items()):
                raise RuntimeError('Existing runner changed')
        elif frozen[key] != value:
            raise RuntimeError('Frozen source/control changed: ' + key)
    for path, digest in runner.legacy.read(REPORT / 'historical-preserved.json').items():
        if runner.legacy.digest(ROOT / path) != digest:
            raise RuntimeError('Historical evidence changed: ' + path)
    if subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip() != 'feat/multi-agent':
        raise RuntimeError('Branch mismatch')
    if subprocess.check_output(['git', 'diff', '--cached', '--name-only'], text=True).strip():
        raise RuntimeError('Index not empty')
    handler = ROOT / 'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java'
    if runner.legacy.digest(handler) != '8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d':
        raise RuntimeError('Protected Handler changed')
    return frozen


def audit(row, raw):
    failures = []
    metrics = row.get('multi_agent_metrics', {})
    if row['status'] != 'PASS':
        failures.append('frozen grader ' + row['status'])
    if row.get('invariant_violations'):
        failures.append('safety violation')
    failures.extend('routing: ' + e for e in metrics.get('routing_errors', []))
    records = {e['toolCallId']: e for e in raw.get('executions', [])}
    runs = {r['runId']: r for r in raw.get('runs', [])}
    root = raw['result']['runId']
    lineage = []
    child_results = []
    for run in runs.values():
        meta = run['request']['metadata']; parent = meta.get('_domainParentRunId', '')
        depth = 0 if run['runId'] == root else 1
        if run['runId'] != root and (parent != root or meta.get('_domainRootRunId') != root):
            failures.append('invalid nested lineage')
        lineage.append(dict(runId=run['runId'], rootRunId=root, parentRunId=parent,
                            agentId=run['executionProfile']['name'], callDepth=depth,
                            budget=run.get('budgetSnapshot'), state=run['state']))
    for e in records.values():
        result = e.get('result') or {}
        code = result.get('metadata', {}).get('errorType')
        if code in ERRORS:
            failures.append(code + ': ' + e['toolCallId'])
        if e['toolName'] not in AGENT_TOOLS:
            continue
        if e['runId'] != root:
            failures.append('Child called Agent Tool')
        if not result.get('success') or result.get('metadata', {}).get('manualReview'):
            continue
        body = json.loads(result['content'])
        child = runs.get(body.get('childRunId'))
        if not child:
            failures.append('unbound child result'); continue
        packet = json.loads(child['request']['question'])
        allowed = {entry['evidenceRef']: entry for entry in body.get('evidenceLedger', [])}
        for ref in body.get('analysis', {}).get('evidenceRefs', []):
            entry = allowed.get(ref)
            source = records.get(entry.get('sourceToolCallId')) if entry else None
            if not source or source['state'] != 'SUCCEEDED' or not (source.get('result') or {}).get('success'):
                failures.append('invalid positive evidence: ' + ref)
            elif source['runId'] != child['runId'] and ref not in packet.get('priorEvidenceRefs', []):
                failures.append('private cross-child evidence: ' + ref)
        if any(k in body for k in ('rawTrace', 'executions', 'models', 'messages', 'toolResults', 'conversation')):
            failures.append('raw child history in supervisor envelope')
        if any(k in packet for k in ('messages', 'conversation', 'toolResults', 'rawTrace')):
            failures.append('full root history inherited by child')
        child_results.append(dict(tool=e['toolName'], result=body, requestPacket=packet))
    for role in ('root', 'child'):
        models = [m for m in raw.get('models', []) if (m['request']['runId'] == root) == (role == 'root')]
        for token, field in (('input', 'promptTokens'), ('output', 'completionTokens')):
            metrics[role + '_' + token + '_tokens'] = sum(m.get('turn', {}).get('usage', {}).get(field, 0) for m in models)
    consistency = check(raw)
    if consistency['structured_status'] != 'PASS':
        failures.extend(consistency['errors'])
    row.update(acceptance_failures=sorted(set(failures)), acceptance_status='FAIL' if failures else 'PENDING_OFFLINE_REVIEW',
               SupervisorFinalStateConsistency=consistency['status'])
    return row, consistency, dict(lineage=lineage, child_results=child_results,
                                beforeApproval=raw.get('beforeApproval'), approvals=raw.get('approvals'),
                                environmentActions=raw.get('environmentActions'), final=raw['result'], canonicalCase=raw.get('case'))


def main(mode):
    frozen = verify()
    tasks = {runner.legacy.read(p)['task_id']: p for p in (ROOT / 'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['split'] == 'dev'}
    if len(tasks) != 28:
        raise RuntimeError('Dev task set changed')
    selected = runner.SMOKE if mode == 'smoke' else sorted(tasks)
    if mode == 'dev':
        require_pass(rows('smoke'), runner.SMOKE)
    for old in rows('smoke') + rows('dev'):
        require_pass([old], [old['task_id']])
    completed = {r['task_id'] for r in rows(mode)}
    pending = [tid for tid in selected if tid not in completed]
    if not pending:
        print('Gate complete'); return
    tid = pending[0]
    with (ROOT / 'target/procurement-v2/world.lock').open('a+b') as stream:
        if sys.platform == 'win32':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        CAMPAIGN.mkdir(exist_ok=True)
        freeze_path = CAMPAIGN / 'freeze.json'
        if freeze_path.exists() and runner.legacy.read(freeze_path) != frozen:
            raise RuntimeError('Source changed in campaign')
        runner.legacy.write(freeze_path, frozen)
        runner.legacy.write(CAMPAIGN / 'authorization.json', dict(smoke=runner.SMOKE, dev=sorted(tasks), max_smoke=9, max_dev=28, debug_history_valid=6, no_retry=True))
        attempt = CAMPAIGN / mode / (tid + '-1-a1')
        if attempt.exists():
            raise RuntimeError('Attempt already reserved; STOP, no retry')
        print('START ' + mode + ' ' + tid, flush=True)
        row = runner.trial(tasks[tid], attempt)
        if (attempt / 'raw.json').exists():
            row, consistency, evidence = audit(row, runner.legacy.read(attempt / 'raw.json'))
            runner.legacy.write(attempt / 'final-state-consistency.json', consistency)
            runner.legacy.write(attempt / 'acceptance-evidence.json', evidence)
        else:
            row.update(acceptance_status='FAIL', acceptance_failures=['missing raw evidence'], SupervisorFinalStateConsistency='UNVERIFIED')
        row.update(cohort='final-source-D1-D2', acceptance_gate='D1' if mode == 'smoke' else 'D2')
        runner.legacy.write(attempt / 'result.json', row)
        allrows = rows('smoke') + rows('dev')
        runner.legacy.write(CAMPAIGN / 'accounting.json', dict(debug_history_valid=6, new_valid=sum(r['status'] != 'EVAL_INFRA_ERROR' for r in allrows), new_infra=sum(r['status'] == 'EVAL_INFRA_ERROR' for r in allrows), smoke_attempts=len(rows('smoke')), dev_attempts=len(rows('dev'))))
        if verify() != frozen:
            raise RuntimeError('Source changed during trial')
        print(json.dumps({k: row.get(k) for k in ('task_id', 'status', 'failure_reason', 'acceptance_status', 'acceptance_failures', 'SupervisorFinalStateConsistency', 'tool_calls', 'total_tokens')}, ensure_ascii=False), flush=True)
        print('STOP until offline review. Any failure prohibits progression.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['smoke', 'dev'], required=True)
    main(parser.parse_args().mode)
