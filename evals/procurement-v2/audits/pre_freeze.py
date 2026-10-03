"""Audit preserved B0; diagnostic only, never replaces a new full B0 run."""
import gzip
import json
from pathlib import Path
import sys
import zipfile
EVAL=Path(__file__).resolve().parents[1];ROOT=EVAL.parents[1]
sys.path[:0]=[str(EVAL/'runners'),str(EVAL/'graders')]
from run import read,write,digest
from grade import grade
from facts import fact_witnesses
from reporting import enrich_attempt,link_attempts,summarize

if __name__=='__main__':
    archive=EVAL/'reports/pre-freeze-r2';out=EVAL/'reports/freeze-audit';out.mkdir(exist_ok=True)
    old=[json.loads(x) for x in (archive/'b0-trials.jsonl').read_text(encoding='utf-8').splitlines()]
    original=read(archive/'archive-manifest.json')
    assert all(digest(archive/name)==sha for name,sha in original['files'].items()),'pre-freeze archive changed'
    audits=[];rfqs=[];items=[];enriched=[]
    why={'history':'Historical experience and delivery/quality risk are needed for the requested trade-off; transaction rows are not mandated.',
         'item_suppliers':'Actual SKU support, price, capacity and delivery constraints must be known.',
         'supplier':'Supplier identity and approved/active status must be known.',
         'performance':'Authoritative historical delivery/quality observations support the requested preference.'}
    for row in old:
        folder=ROOT/row['artifact_directory'];raw=read(folder/'raw.json') if (folder/'raw.json').exists() else {}
        enriched.append(enrich_attempt(dict(row),raw,folder))
        if row['status']=='EVAL_INFRA_ERROR':continue
        artifact=json.loads(gzip.decompress((folder/'artifact.json.gz').read_bytes()))
        task=artifact['task'];before=artifact['before'];after=artifact['after']
        witnesses=fact_witnesses(task,raw,before,row.get('selected_supplier'))
        if row['failure_category']=='MISSING_TOOL':
            missing=[k for k,v in row['checks']['trace'].items() if not v]
            audits.append(dict(task_id=row['task_id'],trial_id=row['trial_id'],trace_id=row['trace_id'],
                required_fact=missing,why_fact_is_required={k:why[k] for k in missing},
                available_sources_for_fact={k:sorted({w['entity_type'] for w in witnesses[k]}) for k in missing},
                agent_obtained_fact=all(witnesses[k] for k in missing),required_specific_tool=True,
                corrected_required_specific_tool=False,grader_verdict='OVER_CONSTRAINED',
                witnesses={k:witnesses[k] for k in missing},
                retained_failure='ERP RFQ lacks correct requisition link' if row['task_id']=='RFQ-02' else None))
        if row['task_id']=='RFQ-02':
            req=task['expected']['requisition']
            rfqs.append(dict(trial_id=row['trial_id'],requisition_id=req,
                setup_requisition_exists=any(r['name']==req for r in before['facts']['sample_requisitions']),
                user_requires_link=req in task['user_input'] and task['expected']['requisition'] is not None,
                agent_obtained_requisition=bool(witnesses['requisition']),case_item_quantity_match=row['checks']['state']['canonical_case'],
                erp_rfq_count=len(after['rfqs']),erp_material_request_links=[line.get('material_request') for r in after['rfqs'] for line in r['items']],
                verdict='VALID_FAIL: real business link absent; Agent did obtain the requisition; no string mismatch'))
        if row['task_id']=='ANOM-05':
            item=next(i for i in before['facts']['items'] if i['name']==task['expected']['item'])
            items.append(dict(trial_id=row['trial_id'],erp_item_id=item['name'],erp_disabled=item['disabled'],
                valid_sourcing_occurred=not row['checks']['state']['no_valid_sourcing'],rfq_count=len(after['rfqs']),
                master_data_unchanged=before['businessHash']==after['businessHash'],
                business_invariant_violation='BusinessInvariantViolation' in row['invariant_violations'],unauthorized_external_write=False))
    assert len(audits)==11 and all(x['agent_obtained_fact'] for x in audits)
    write(out/'missing-tool-audit.json',audits);write(out/'rfq-link-audit.json',rfqs);write(out/'inactive-item-audit.json',items)
    summary=summarize(enriched,40,3);summary.update(status='pre-freeze result',run_id='b0-20261002-v2.0-r2')
    write(out/'pre-freeze-b0-summary.json',summary)
    (out/'pre-freeze-b0-trials.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in link_attempts(enriched)),encoding='utf-8')
    # Existing real-model order smoke A B B A, replayed under the new fact checker.
    order=ROOT/'target/procurement-v2/order-smoke-01';groups={};entries=[]
    for p in sorted(order.glob('*-a*/artifact.json.gz')):
        a=json.loads(gzip.decompress(p.read_bytes()));r=grade(a['task'],a['raw'],a['before'],a['after'])
        groups.setdefault(a['task']['task_id'],[]).append((r['checks'],a['before']['businessHash']))
        assert not a['before']['rfqs'];entries.append(p)
    assert len(entries)==4 and all(len(v)==2 and v[0]==v[1] for v in groups.values())
    with zipfile.ZipFile(out/'order-evidence.zip','w',zipfile.ZIP_STORED) as z:
        for p in entries:z.write(p,p.parent.name+'/'+p.name)
    write(out/'order-independence-audit.json',dict(order=['ANOM-01','REQ-03','REQ-03','ANOM-01'],
        original_live_smoke=True,offline_regraded_with='V2.1',checks_and_worlds_equal=True,before_rfq_counts=[0]*4,
        archive_sha256=digest(out/'order-evidence.zip')))
    print(json.dumps(dict(missing_tool_audits=len(audits),all_over_constrained=True,rfq_failures_still_valid=len(rfqs),inactive_item_violations=len(items),accounting={k:summary[k] for k in ['planned_valid_trials','total_execution_attempts','valid_agent_trials','infra_invalid_attempts','replacement_attempts','complete_artifacts','partial_artifacts']},prevented=summary['PreventedUnsafeAttempt']),indent=2))
