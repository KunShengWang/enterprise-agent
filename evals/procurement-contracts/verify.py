"""Offline verification of Phase 2.5 against frozen V2.1, without changing B0."""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[2]
EVAL=ROOT/'evals/procurement-v2'
sys.path[:0]=[str(EVAL/'runners'),str(EVAL/'graders')]
from run import read,write,digest,manifest,schema_hash
from grade import grade
from reporting import summarize

def main():
    reports=ROOT/'evals/procurement-contracts/reports'
    summary=read(reports/'business-stabilized-summary.json');baseline=read(EVAL/'baseline-v2.1.json')
    freeze=summary['freeze'];current=manifest()
    assert all(current[k]==v for k,v in freeze.items() if k!='experiment'),'source drift'
    assert digest(ROOT/'evals/procurement-contracts/run.py')==freeze['experiment']['output_wrapper_sha256'],'output wrapper drift'
    assert all(digest(ROOT/p)==v for p,v in freeze['experiment']['frozen_eval_file_bytes'].items()),'frozen B0 changed'
    rows=[json.loads(l) for l in (reports/'business-stabilized-trials.jsonl').read_text(encoding='utf-8').splitlines()]
    for k,v in summarize(rows,40,3).items():assert summary[k]==v,k
    valid=[r for r in rows if r['status']!='EVAL_INFRA_ERROR'];assert len(valid)==120
    assert len({r['trial_id'] for r in valid})==120
    counts=collections.Counter(r['task_id'] for r in valid);assert len(counts)==40 and set(counts.values())=={3}
    index=read(reports/'business-stabilized-evidence-index.json')
    assert index['run_id']==summary['run_id']
    assert digest(reports/'business-stabilized-evidence.zip')==index['archive_sha256']
    infra=read(reports/'business-stabilized-infra-evidence-index.json')
    assert infra['attempt_ids']==[r['attempt_id'] for r in summary['infra_attempt_ledger']]
    assert digest(reports/'business-stabilized-infra-evidence.zip')==infra['archive_sha256']
    with zipfile.ZipFile(reports/'business-stabilized-infra-evidence.zip') as z:
        assert z.testzip() is None
        assert set(z.namelist())=={e['path'] for e in infra['entries']}
        for entry in infra['entries']:assert hashlib.sha256(z.read(entry['path'])).hexdigest()==entry['sha256']
    snapshot=read(reports/'production-review-snapshot-index.json')
    assert digest(reports/'production-review-snapshot.zip')==snapshot['archive_sha256']
    assert snapshot['production_working_tree_hash']==freeze['production']['sha256']
    with zipfile.ZipFile(reports/'production-review-snapshot.zip') as z:
        assert z.testzip() is None
        for path,sha in snapshot['files'].items():
            assert digest(ROOT/path)==sha==hashlib.sha256(z.read(path)).hexdigest()
    by_folder={Path(r['artifact_directory']).name:r for r in rows}
    run_ids=set();case_ids=set();approval_ids=set();model_warnings=[];regraded=0
    with zipfile.ZipFile(reports/'business-stabilized-evidence.zip') as z:
        assert z.testzip() is None
        for entry in index['artifacts']:
            payload=z.read(entry['path']);assert hashlib.sha256(payload).hexdigest()==entry['sha256']
            row=by_folder[Path(entry['path']).parent.name];assert row['artifact_sha256']==entry['sha256']
            a=json.loads(gzip.decompress(payload));raw=a['raw']
            for k,v in grade(a['task'],raw,a['before'],a['after']).items():assert row[k]==v,(row['trial_id'],k)
            regraded+=1
            if row['status']=='EVAL_INFRA_ERROR':continue
            assert not a['before']['rfqs'],'RFQ state leak'
            rid=raw['result']['runId'];assert rid not in run_ids;run_ids.add(rid)
            if raw.get('case'):
                cid=raw['case']['caseId'];assert cid not in case_ids;case_ids.add(cid)
            ids={x['approvalId'] for x in raw.get('approvals',[])};assert not ids&approval_ids;approval_ids.update(ids)
            assert all(x['runId']==rid for x in raw.get('executions',[]))
            controls=row['controls'];assert controls['profile_prompt_sha256']==baseline['prompt_hash']
            assert controls['model_options']==baseline['model_settings']
            hashes={schema_hash(x['request']['tools']) for x in raw['models']}
            assert sorted(hashes)==controls['schema_sha256'] and hashes<=set(baseline['tool_schema_hash'])
            if row['resolved_models']!=baseline['resolved_models']:model_warnings.append(row['trial_id'])
    assert regraded==summary['complete_artifacts']
    assert digest(ROOT/'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java').upper()=='8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D'
    old=read(EVAL/'reports/legacy-regression.json')
    assert all(digest(ROOT/x['path'])==x['sha256'] for x in old['fixtures'])
    known=[r for r in valid if r['task_id'] in ('ANOM-05','RFQ-02')]
    audit=dict(passed=True,run_id=summary['run_id'],regraded_artifacts=regraded,valid_trials=120,
        frozen_eval_and_b0_unchanged=True,baseline_prompt_and_model_options_and_visible_tool_schemas_unchanged=True,
        model_comparability_warning_trials=model_warnings,before_rfq_empty_trials=120,distinct_run_ids=len(run_ids),
        distinct_case_ids=len(case_ids),distinct_approval_ids=len(approval_ids),no_cross_trial_leak=True,
        protected_handler_unchanged=True,legacy_fingerprints_unchanged=5,known_defect_trials_passed=sum(r['passed'] for r in known),
        archive_sha256=index['archive_sha256'],verification_script_sha256=digest(Path(__file__)))
    audit.update(infra_attempts_archived=len(infra['attempt_ids']),production_review_snapshot_verified=True,output_wrapper_unchanged=True)
    write(reports/'business-stabilized-integrity-audit.json',audit)
    print(json.dumps(audit,indent=2))

if __name__=='__main__':main()
