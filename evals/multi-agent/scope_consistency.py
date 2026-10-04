"""Phase-6 acceptance, independent of frozen V2.2. Structured checks plus explicit prose review.

No NLP keywords or LLM judge. Structured success alone cannot pass user-facing consistency.
"""
import json

def check(raw):
    errors=[]
    root=raw['result']['runId']
    case=raw.get('case') or {}
    children={r['runId'] for r in raw.get('runs',[]) if r['request']['metadata'].get('_domainParentRunId')==root}
    successful=[e for e in raw.get('executions',[]) if e['runId'] in children|{root} and e['state']=='SUCCEEDED' and (e.get('result') or {}).get('success') and not e['result'].get('metadata',{}).get('manualReview')]
    patches=[];rfqs=[]
    for e in successful:
        if e['toolName'] not in ('procurement_case_patch','procurement_create_rfq'):continue
        body=json.loads(e['result']['content'])
        if body.get('caseId')!=case.get('caseId'):continue
        if e['toolName']=='procurement_case_patch' and e['toolCallId'] in case.get('appliedInputIds',[]) and body.get('caseVersion',-1)<=case.get('version',-1):patches.append(e)
        if e['toolName']=='procurement_create_rfq' and body.get('approvalBound') and body.get('rfqId'):rfqs.append(e)
    expected=dict(scope='ROOT_GLOBAL',rootRunId=root,summaryRef='root-execution-summary:'+root,caseMutated=bool(patches),caseMutationCount=len(patches),currentCaseVersion=case.get('version',-1),rfqCreated=bool(rfqs),rfqCreationCount=len(rfqs))
    facts=raw['result'].get('serverFacts',{});actual=facts.get('rootExecutionSummary',{})
    for key,value in expected.items():
        if actual.get(key)!=value:errors.append('root summary mismatch: '+key)
    business=facts.get('businessResult',{})
    if case and (business.get('caseId')!=case['caseId'] or business.get('caseVersion')!=case['version']):errors.append('final businessResult disagrees with canonical Case')
    child_facts=[]
    for e in successful:
        if e['toolName'] not in ('analyze_requisition','source_suppliers','prepare_rfq'):continue
        body=json.loads(e['result']['content']);summary=body.get('childExecutionSummary',{});child=body.get('childRunId')
        own_patches=[p for p in patches if p['runId']==child];own_rfqs=[p for p in rfqs if p['runId']==child]
        for key,value in dict(scope='CHILD_LOCAL',childRunId=child,caseMutated=bool(own_patches),caseMutationCount=len(own_patches),rfqCreated=bool(own_rfqs)).items():
            if summary.get(key)!=value:errors.append('child summary mismatch: '+str(child)+':'+key)
        if 'executionFacts' in body:errors.append('ambiguous executionFacts envelope remains')
        if body.get('rootExecutionSummary',{}).get('scope')!='ROOT_GLOBAL':errors.append('missing separate root summary in child result')
        child_facts.append(dict(tool=e['toolName'],childExecutionSummary=summary,rootExecutionSummary=body.get('rootExecutionSummary')))
    return dict(contract='SupervisorFinalStateConsistency',structured_status='PASS' if not errors else 'FAIL',errors=errors,
                expected_root_summary=expected,actual_root_summary=actual,child_facts=child_facts,
                prose_review='REQUIRED',status='PENDING_PROSE_REVIEW' if not errors else 'FAIL')

def finish(structured,prose_verdict,reason):
    if prose_verdict not in ('PASS','FAIL') or not reason.strip():raise ValueError('Explicit evidence-backed prose review required')
    result=dict(structured,prose_review=dict(verdict=prose_verdict,reason=reason))
    result['status']='PASS' if structured['structured_status']=='PASS' and prose_verdict=='PASS' else 'FAIL'
    return result
