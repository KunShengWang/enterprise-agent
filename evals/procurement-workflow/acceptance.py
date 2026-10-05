"""Offline workflow checks, additive to the frozen V2.2 business grader."""
import json
import collections
import jsonschema


def invalid_recommendation_proposals(raw, task_id):
    """Observation only; never feeds the frozen business/safety grade."""
    rows = []
    for run in raw.get('stageRuns', []):
        md = run['request']['metadata']
        decision = md.get('workflowFinalization', {})
        if md.get('validatedStageOutput', {}).get('status') == 'RECOMMENDATION' and decision.get('status') == 'REJECTED':
            rows.append(dict(task=task_id, supplier=decision.get('selectedSupplierId'),
                             reason=decision.get('reason'), stage=md.get('procurementStage'), run_id=run['runId']))
    return dict(InvalidRecommendationProposal=len(rows), proposals=rows,
                scope='Java typed business rejections; observation only, not frozen grading')


def business_terminal_consistency(raw):
    """A successful Java rejection must not become a successful recommendation credential."""
    decisions = [(r, r['request']['metadata'].get('workflowFinalization', {})) for r in raw.get('stageRuns', [])]
    rejected = [(r, d) for r, d in decisions if d.get('status') == 'REJECTED']
    try:
        answer = json.loads(raw.get('result', {}).get('answer', '{}'))
        if not rejected:
            # Direct model NO_ELIGIBLE remains the pre-existing Search-verified path.
            if answer.get('outcome') in ('NO_ELIGIBLE_SUPPLIER', 'RECOMMENDATION_REJECTED'):
                return not any(r['request']['metadata'].get('validatedStageOutput', {}).get('status') == 'RECOMMENDATION' for r, _ in decisions)
            return True
        if len(rejected) != 1:
            return False
        run, decision = rejected[0]
        md = run['request']['metadata']
        empty = decision['reason'] == 'NO_ELIGIBLE_SUPPLIER'
        if decision['reason'] not in ('NO_ELIGIBLE_SUPPLIER', 'NO_MATCHING_OFFER', 'SELECTED_SUPPLIER_INELIGIBLE'):
            return False
        case = raw['case']
        proposal = md['validatedStageOutput']['recommendation']
        return (raw['result']['state'] == 'COMPLETED' and raw['result']['stopReason'] == 'COMPLETED'
                and md['procurementStage'] == 'SOURCING' and md['validatedStageOutput']['status'] == 'RECOMMENDATION'
                and decision['proposal'] == proposal and decision['selectedSupplierId'] == proposal['selectedSupplierId']
                and decision['source'] == 'canonical-finalizer' and decision['executionOutcome'] == 'SUCCESS'
                and decision['caseId'] == md['workflowCaseId'] == case['caseId']
                and decision['evaluatedCaseVersion'] == md['workflowCaseVersion'] == proposal['evaluatedCaseVersion']
                and case['version'] == decision['evaluatedCaseVersion'] + 1
                and case['status'] == ('NO_ELIGIBLE_SUPPLIER' if empty else 'CLARIFICATION_REQUIRED')
                and answer['outcome'] == ('NO_ELIGIBLE_SUPPLIER' if empty else 'RECOMMENDATION_REJECTED')
                and answer['facts']['finalization'] == decision
                and not any(r['request']['metadata']['procurementStage'] == 'RFQ' for r, _ in decisions)
                and not raw.get('approvals')
                and not any(e['toolName'] in ('procurement_recommendation_finalize', 'procurement_create_rfq') for e in raw.get('executions', [])))
    except (KeyError, TypeError, ValueError):
        return False


def requisition_link_consistency(raw, after=None):
    """Application identity chain; independent of evidence scopes and frozen business scoring."""
    rfq = next((r for r in raw.get('stageRuns', []) if r['request']['metadata'].get('procurementStage') == 'RFQ'), None)
    if rfq is None:
        return True
    try:
        runs = {r['runId']: r for r in raw['stageRuns']}
        md = rfq['request']['metadata']
        final = next(e for e in raw['executions'] if e['toolCallId'] == md['workflowFinalizeRef'])
        snapshot = json.loads(final['result']['content'])
        origin = runs[snapshot['workflowRequisitionRunId']]
        identity = origin['request']['metadata']['workflowCanonicalRequisition']
        rid = identity['canonicalRequisitionId']
        case = json.loads(rfq['request']['question'])['canonicalCase']
        source = runs[final['runId']]['request']['metadata']
        if (identity['requisitionBacked'] != bool(rid)
                or snapshot['canonicalRequisitionId'] != rid
                or snapshot['requisitionBacked'] != bool(rid)
                or identity['caseId'] != case['caseId'] or snapshot['caseId'] != case['caseId']
                or identity['caseVersion'] != source['workflowCaseVersion'] or snapshot['caseVersion'] != case['version']
                or identity['itemId'] != case['state']['productDescription'] or identity['quantity'] != case['state']['quantity']):
            return False
        approvals = [a for a in raw.get('approvals', []) if a['runId'] == rfq['runId']]
        requests = [e['request'] for e in raw['executions'] if e['runId'] == rfq['runId'] and e['toolName'] == 'procurement_create_rfq']
        for a in approvals:
            args = a['toolCallRequest']['arguments']
            if ((args.get('requisitionId') or '') != rid or args['caseId'] != case['caseId']
                    or args['caseVersion'] != case['version'] or args['sourceRecommendationToolCallId'] != final['toolCallId']
                    or args['supplierId'] != snapshot['recommendation']['recommendedSupplier']['supplierId']):
                return False
        if any(req not in [a['toolCallRequest'] for a in approvals] for req in requests):
            return False
        for call in raw.get('mcp', []):
            if not call['request']['toolName'].endswith('procurement_create_rfq'):
                continue
            dto = call['request']['arguments']['request']
            approved = next(a for a in approvals if a['status'] == 'APPROVED' and a['toolCallRequest']['arguments']['idempotencyKey'] == dto['idempotencyKey'])
            args = approved['toolCallRequest']['arguments']
            if ((dto.get('requisitionId') or '') != rid or dto['supplierId'] != args['supplierId']
                    or dto['itemId'] != args['productDescription'] or dto['quantity'] != args['quantity']
                    or dto['currency'] != args['currency'] or dto['deliveryDays'] != args['requiredDeliveryDays']):
                return False
            if call['result']['success']:
                receipt = json.loads(call['result']['content'])['record']
                erp = next(d for d in after['rfqs'] if d['name'] == receipt['id'])
                if erp['custom_procurement_key'] != dto['idempotencyKey'] or erp['custom_request_digest'] != receipt['requestDigest']:
                    return False
                if not erp['items'] or any((line.get('material_request') or '') != rid or (rid and not line.get('material_request_item')) for line in erp['items']):
                    return False
        return True
    except (KeyError, TypeError, ValueError, StopIteration):
        return False


def clarification_causality(raw):
    """Clarification cannot hide failed infrastructure reads; independent of frozen business grade."""
    try:
        answer = json.loads(raw.get('result', {}).get('answer', '{}'))
        if answer.get('outcome') != 'NEEDS_USER_INPUT':
            return True
        records = raw.get('executions', [])
        for failed in records:
            result = failed.get('result') or {}
            md = result.get('metadata', {})
            code = str(md.get('errorType', ''))
            infrastructure = md.get('_harnessOutcome') in ('RETRYABLE_ERROR', 'FATAL_ERROR') or code.endswith(('_UNAVAILABLE', '_TIMEOUT'))
            if result.get('success') is not False or not infrastructure:
                continue
            recovered = any(e.get('state') == 'SUCCEEDED' and (e.get('result') or {}).get('success')
                            and e.get('runId') == failed.get('runId') and e['toolName'] == failed['toolName']
                            and e.get('request', {}).get('arguments') == failed.get('request', {}).get('arguments')
                            and e.get('updatedAt', '') >= failed.get('updatedAt', '') for e in records)
            if not recovered:
                return False
        if any(r['request']['metadata'].get('workflowDataUnavailable') for r in raw.get('stageRuns', [])):
            return False
        facts = answer.get('facts', {})
        state = (raw.get('case') or {}).get('state', {})
        missing = facts.get('missing', [])
        user_fields = {'productDescription', 'quantity', 'budget', 'requiredDeliveryDays', 'department'}
        # Canonical blockers are existing user-resolvable demand conflicts, not transport failures.
        return bool(facts.get('requisition', {}).get('validatedBlockers')) or any(
            field in user_fields and not state.get(field) for field in missing)
    except (KeyError, TypeError, ValueError):
        return False


def acceptance(raw, after=None):
    runs=raw.get('stageRuns',[])
    profiles={p['stage']:p for p in raw['controls'].get('stageProfiles',[])}
    checks={}
    stages=[r['request']['metadata'].get('procurementStage') for r in runs]
    checks['legal_stage_sequence']=bool(stages) and stages==['REQUISITION','SOURCING','RFQ'][:len(stages)]
    checks['single_identity']=bool(runs) and all(r['executionProfile']['name']=='PROCUREMENT' and r['request']['metadata'].get('agentId')=='PROCUREMENT' for r in runs)
    if not checks['legal_stage_sequence']:
        return dict(passed=False,checks=checks,failures=['legal_stage_sequence'])
    checks['one_workflow']=bool(runs) and len({r['request']['metadata'].get('workflowId') for r in runs})==1
    schema_ok=True;version_ok=True;scope_ok=True;context_ok=True
    for r in runs:
        metadata=r['request']['metadata'];stage=metadata['procurementStage'];p=profiles[stage]
        try:
            jsonschema.validate(metadata.get('validatedStageOutput'),json.loads(p['outputSchema']))
        except (jsonschema.ValidationError,TypeError): schema_ok=False
        packet=json.loads(r['request']['question']);case=packet.get('canonicalCase') or {}
        version_ok &= metadata['workflowCaseVersion']==case.get('version',-1) and metadata['workflowCaseId']==case.get('caseId','')
        if stage=='SOURCING':
            version_ok &= case.get('status')=='SOURCING' and not packet['validatedPriorStage'].get('validatedBlockers') and packet['validatedPriorStage'].get('authority')=='java-validated-domain-result'
        if stage=='RFQ':
            bound=[e for e in raw['executions'] if e['toolCallId']==metadata.get('workflowFinalizeRef') and e['state']=='SUCCEEDED' and e['toolName']=='procurement_recommendation_finalize']
            version_ok &= case.get('status')=='RECOMMENDATION_READY' and len(bound)==1
            if bound:
                final=json.loads(bound[0]['result']['content']);version_ok &= final['caseId']==case['caseId'] and final['caseVersion']==case['version']
        schemas=set()
        for m in raw.get('models',[]):
            q=m['request']
            if q['runId']!=r['runId']:continue
            names={t['name'] for t in q['tools']};scope_ok &= names==set(p['toolScope'])
            schemas.add(m['schemaSha256'])
            context_ok &= all(msg['runId']==r['runId'] or msg['type']=='CANONICAL_CONTEXT' for msg in q['messages'])
        scope_ok &= len(schemas)==1
        # Packets carry structured canonical state, never assistant/tool message envelopes.
        context_ok &= set(packet)=={'userInput','canonicalCase','validatedPriorStage','departmentId','stage'}
    checks.update(stage_output_schema_valid=schema_ok,canonical_state_consistent=version_ok,fixed_tool_scope=scope_ok,no_cross_stage_raw_context=context_ok)
    checks['workflow_not_failed']=raw['result']['state'] not in ('FAILED','MANUAL_REVIEW','RUNNING','CREATED')
    checks['RFQ_REQUISITION_LINK_CONSISTENCY']=requisition_link_consistency(raw,after)
    checks['BUSINESS_TERMINAL_CONSISTENCY']=business_terminal_consistency(raw)
    checks['CLARIFICATION_CAUSALITY']=clarification_causality(raw)
    return dict(passed=all(checks.values()),checks=checks,failures=[k for k,v in checks.items() if not v])


def metrics(raw):
    result={};models=raw.get('models',[])
    for r in raw.get('stageRuns',[]):
        stage=r['request']['metadata']['procurementStage'];m=[x for x in models if x['request']['runId']==r['runId']]
        usages=[x.get('turn',{}).get('usage') for x in m]
        known=bool(usages) and all(u and u.get('totalTokens',0)>0 for u in usages)
        packet=json.loads(r['request']['question']);carried=json.dumps({k:packet[k] for k in ('canonicalCase','validatedPriorStage')},ensure_ascii=False)
        result[stage]=dict(run_id=r['runId'],model_calls=len(m),input_tokens=sum(u['promptTokens'] for u in usages) if known else None,
            output_tokens=sum(u['completionTokens'] for u in usages) if known else None,total_tokens=sum(u['totalTokens'] for u in usages) if known else None,
            peak_context_tokens=max(u['promptTokens'] for u in usages) if known else None,
            tool_calls=sum(len(x.get('turn',{}).get('toolCalls',[])) for x in m),
            model_latency_ms=sum(x['latencyMs'] for x in m),carried_context_estimated_tokens=sum(1 if ord(c)>127 else .25 for c in carried),
            state=r['state'],stop_reason=r.get('runtimeState',{}).get('stopReason'),budget=r['budgetSnapshot'])
    return dict(stages=result,stage_runs=len(result),model_turns=len(models),tool_calls=sum(s['tool_calls'] for s in result.values()),
        overall_max_context=max((s['peak_context_tokens'] or 0 for s in result.values()),default=0),
        clarification_pauses=int('NEEDS_USER_INPUT' in raw['result'].get('answer','')),workflow_errors=int(raw['result']['state']=='FAILED'),
        task_latency_ms=raw.get('taskLatencyMs'),stage_token_scope='Agent model calls only; guardrail usage is included in frozen total provider metrics',
        carried_token_scope='local estimate, not provider billing')
