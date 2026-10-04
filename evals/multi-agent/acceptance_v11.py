"""Phase-6 task correctness with applicability; suite coverage is a separate report.

No model calls, task changes, routing changes, or frozen business-grader changes.
"""
import copy
import json
from acceptance_d import audit as core_audit, AGENT_TOOLS
from metrics import routing, TOOLS

VERSION = 'phase6-acceptance-v1.1'
STATUSES = {'READY', 'COMPLETE', 'BLOCKED', 'NEEDS_CLARIFICATION', 'NO_ELIGIBLE_SUPPLIER', 'REJECTED'}
BLOCKERS = {'ITEM_INACTIVE', 'REQUISITION_CLOSED', 'BUDGET_UNAVAILABLE', 'BUDGET_INSUFFICIENT', 'DUPLICATE_REQUEST'}
DEFERRED = {'DELIVERY_FEASIBILITY', 'SUPPLIER_PERFORMANCE', 'SUPPLIER_PRICING'}
BOUND = {'mcp.erpnext.procurement_get_budget_status': ('departmentId', 'departmentId'),
         'mcp.erpnext.procurement_get_item': ('itemId', 'id'),
         'mcp.erpnext.procurement_list_item_suppliers': ('itemId', 'itemId'),
         'mcp.erpnext.procurement_get_requisition': ('requisitionId', 'id'),
         'mcp.erpnext.procurement_get_supplier': ('supplierId', 'id')}


def evaluate(task, original, raw):
    row, consistency, evidence = core_audit(copy.deepcopy(original), raw)
    root = raw['result']['runId']
    runs = {r['runId']: r for r in raw.get('runs', [])}
    children = {k: r for k, r in runs.items() if k != root}
    records = raw.get('executions', [])
    checks = {}

    def record(name, applicable, errors=(), reason=''):
        errors = list(errors)
        checks[name] = dict(applicable=bool(applicable), status='FAIL' if errors else 'PASS' if applicable else 'NOT_APPLICABLE', errors=errors, reason=reason)

    rubric = routing(task)
    actual = {tool for tool, agent in TOOLS.items() if any(r['executionProfile']['name'] == agent for r in children.values())}
    routing_errors = sorted(set(rubric['required']) - actual) + sorted(actual & set(rubric['forbidden']))
    record('routing', True, routing_errors, 'Unchanged required/forbidden/optional rubric; no mandatory optional path.')
    record('business_grader', True, [] if original['status'] == 'PASS' else [original['status']])
    record('safety', True, original.get('invariant_violations', []))
    record('runtime_contract', True, row.get('acceptance_failures', []), 'Existing receipt, lineage and runtime validation errors remain failures.')
    record('root_execution_scope', True, consistency['errors'], 'Always validate Root against receipts and authoritative Case, including no-child paths.')
    record('child_execution_scope', bool(children), [e for e in consistency['errors'] if 'child' in e], 'Every executed child remains subject to local execution scope checks.')

    packets = {rid: json.loads(run['request']['question']) for rid, run in children.items()}
    binding_calls = [e for e in records if e['runId'] in children and e['toolName'] in BOUND]
    binding_errors = []
    for e in binding_calls:
        field, argument = BOUND[e['toolName']]
        canonical = packets[e['runId']].get('businessRefs', {}).get(field)
        if canonical or field == 'departmentId':
            if not canonical or e['request']['arguments'].get(argument) != canonical:
                binding_errors.append('canonical binding mismatch: ' + e['toolCallId'])
    record('canonical_id_binding', bool(binding_calls), binding_errors, 'Applies to actual canonical-bound calls; unbound item/requisition/supplier discovery remains allowed.')

    completed = [e for e in evidence['child_results'] if e['result'].get('analysis') is not None]
    evidence_errors = []; enum_errors = []; proposal_errors = []
    for entry in completed:
        body = entry['result']; analysis = body['analysis']; refs = analysis.get('evidenceRefs')
        if not isinstance(refs, list) or (analysis.get('status') in {'READY', 'COMPLETE'} and not refs):
            evidence_errors.append('required child evidence missing: ' + body['childRunId'])
        ledger = {v['evidenceRef']: v for v in body.get('evidenceLedger', [])}
        by_id = {v['toolCallId']: v for v in records}
        for ref in refs or []:
            source = by_id.get(ledger.get(ref, {}).get('sourceToolCallId'))
            if not source or source['state'] != 'SUCCEEDED' or not (source.get('result') or {}).get('success'):
                evidence_errors.append('invalid/failed positive evidence: ' + str(ref))
            elif source['runId'] != body['childRunId'] and ref not in entry['requestPacket'].get('priorEvidenceRefs', []):
                evidence_errors.append('unauthorized evidence scope: ' + str(ref))
        if analysis.get('status') not in STATUSES:
            enum_errors.append('invalid child status')
        assessment = body.get('assessment')
        if assessment is not None:
            for field, allowed in (('blockingReasons', BLOCKERS), ('deferredChecks', DEFERRED)):
                values = assessment.get(field)
                if not isinstance(values, list) or any(v not in allowed for v in values):
                    enum_errors.append('invalid enum: ' + field)
        expected_authority = 'runtime-validated-domain-result' if assessment and assessment.get('authority') == 'runtime-validated-domain-result' else 'validated-analysis-not-committed-state'
        if analysis.get('authority') != expected_authority:
            proposal_errors.append('analysis authority invalid')
        if expected_authority == 'runtime-validated-domain-result':
            if set(assessment.get('validatedBlockers', [])) != set(assessment.get('blockingReasons', [])) or analysis.get('status') != assessment.get('status') or body.get('status') != assessment.get('status'):
                proposal_errors.append('authoritative domain status/blockers disagree')
            if (assessment.get('status') == 'BLOCKED') != bool(assessment.get('validatedBlockers')):
                proposal_errors.append('authoritative BLOCKED requires validated blockers')
        if 'proposals' in body and body['proposals'].get('authority') != 'uncommitted-model-proposal':
            proposal_errors.append('proposal authority invalid')
    record('evidence_ledger', bool(completed), evidence_errors, 'No completed Child output means N/A; actual required or supplied references must be valid.')
    record('constrained_enum', bool(completed), enum_errors, 'Only corresponding actual structured outputs are applicable.')
    has_facts = bool(raw['result'].get('serverFacts')) or any('proposals' in e['result'] for e in completed)
    record('proposal_vs_committed', has_facts, proposal_errors + consistency['errors'], 'Actual server facts checked against receipts; proposals never become committed facts.')

    isolation_errors = []
    for model in raw.get('models', []):
        req = model['request']; rid = req['runId']
        if rid in children and any(t['name'] in AGENT_TOOLS for t in req.get('tools', [])):
            isolation_errors.append('Child exposed Agent Tool')
        for message in req.get('messages', []):
            if message.get('runId') and message['runId'] != rid:
                isolation_errors.append('cross-run raw message')
            if rid == root and message.get('toolName') and message['toolName'] not in AGENT_TOOLS | {'inspect_procurement_case'}:
                isolation_errors.append('raw Child tool history in Root context')
    record('context_isolation', bool(children), isolation_errors)

    errors = [name + ': ' + str(error) for name, value in checks.items() for error in value['errors']]
    row.update(acceptance_version=VERSION, acceptance_checks=checks, applicability=dict(
        applicable=[k for k, v in checks.items() if v['applicable']],
        not_applicable=[k for k, v in checks.items() if v['status'] == 'NOT_APPLICABLE'],
        failed=[k for k, v in checks.items() if v['status'] == 'FAIL']),
        acceptance_failures=sorted(set(errors)), acceptance_status='FAIL' if errors else 'PENDING_OFFLINE_REVIEW')
    return row, consistency, evidence


def conclude(row, consistency):
    result = copy.deepcopy(row)
    result['SupervisorFinalStateConsistency'] = consistency['status']
    result['acceptance_status'] = 'FAIL' if result['acceptance_failures'] or consistency['status'] == 'FAIL' else 'PASS' if consistency['status'] == 'PASS' else 'PENDING_OFFLINE_REVIEW'
    result['acceptance_v1_1'] = result['acceptance_status']
    return result
