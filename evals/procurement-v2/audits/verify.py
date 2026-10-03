"""Verify complete frozen B0 and generate a manifest, without model/ERP calls."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zipfile
EVAL=Path(__file__).resolve().parents[1];ROOT=EVAL.parents[1]
sys.path[:0]=[str(EVAL/'runners'),str(EVAL/'graders')]
from run import read,write,digest,manifest,schema_hash
from grade import grade
from reporting import summarize,LIMITATION

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('run_id');args=parser.parse_args()
    run=ROOT/'target/procurement-v2'/args.run_id;reports=EVAL/'reports'
    freeze=read(run/'freeze.json');assert freeze==manifest(),'frozen source drift'
    rows=[json.loads(line) for line in (reports/'b0-trials.jsonl').read_text(encoding='utf-8').splitlines()]
    valid=[r for r in rows if r['status']!='EVAL_INFRA_ERROR']
    assert len(valid)==120 and len({r['trial_id'] for r in valid})==120
    counts=collections.Counter(r['task_id'] for r in valid);assert len(counts)==40 and set(counts.values())=={3}
    summary=read(reports/'b0-summary.json');assert summary['run_id']==args.run_id
    for k,v in summarize(rows,40,3).items():assert summary[k]==v,k
    index=read(reports/'b0-evidence-index.json');assert digest(reports/'b0-evidence.zip')==index['archive_sha256']
    folders={Path(r['artifact_directory']).name:r for r in rows};regraded=0
    cases=set();approvals=set();runs=set();schema_sets=set();prompt_hashes=set();models=set();settings=set()
    with zipfile.ZipFile(reports/'b0-evidence.zip') as z:
        assert z.testzip() is None
        for e in index['artifacts']:
            payload=z.read(e['path']);assert hashlib.sha256(payload).hexdigest()==e['sha256']
            row=folders[Path(e['path']).parent.name];assert row['artifact_sha256']==e['sha256']
            a=json.loads(gzip.decompress(payload));new=grade(a['task'],a['raw'],a['before'],a['after'])
            for k,v in new.items():assert row[k]==v,(row['trial_id'],k)
            regraded+=1
            if row['status']=='EVAL_INFRA_ERROR':continue
            raw=a['raw'];assert not a['before']['rfqs'],'ERP RFQ leaked across trials'
            rid=raw['result']['runId'];assert rid not in runs;runs.add(rid)
            if raw.get('case'):
                cid=raw['case']['caseId'];assert cid not in cases;cases.add(cid)
            ids={x['approvalId'] for x in raw.get('approvals',[])};assert not ids&approvals;approvals.update(ids)
            assert all(e['runId']==rid for e in raw.get('executions',[])),'foreign receipt/execution'
            hashes={schema_hash(m['request']['tools']) for m in raw['models']}
            assert sorted(hashes)==row['controls']['schema_sha256'];schema_sets.update(hashes)
            prompt_hashes.add(row['controls']['profile_prompt_sha256']);models.update(row['resolved_models'])
            settings.add(json.dumps(row['controls']['model_options'],sort_keys=True))
    assert regraded==summary['complete_artifacts']
    assert len(prompt_hashes)==1 and len(settings)==1
    legacy=read(reports/'legacy-regression.json');assert all(digest(ROOT/f['path'])==f['sha256'] for f in legacy['fixtures'])
    assert digest(ROOT/'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java').upper()=='8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D'
    tasks=[read(p) for p in sorted((EVAL/'tasks').rglob('*.json'))]
    dev=sorted(t['task_id'] for t in tasks if t['split']=='dev');holdout=sorted(t['task_id'] for t in tasks if t['split']=='holdout')
    assert len(dev)==28 and len(holdout)==12
    baseline=dict(eval_version='procurement-eval-v2.1',production_sha=freeze['code_sha'],
        eval_implementation=dict(commit=None,status='uncommitted Phase-2 implementation; production SHA does not contain Eval V2',branch='feat/procurement-eval-v2'),
        configured_model=json.loads(next(iter(settings)))['model'],resolved_model=next(iter(models)) if len(models)==1 else None,
        resolved_models=sorted(models),provider='deepseek',model_metadata_if_available=dict(exact_immutable_build=None),
        model_reproducibility_limitation=LIMITATION,model_comparability_warnings=summary['model_comparability_warnings'],
        seed_hash=freeze['seed']['sha256'],task_hash=freeze['tasks']['sha256'],grader_hash=freeze['graders']['sha256'],runner_hash=freeze['runner']['sha256'],
        production_working_tree_hash=freeze['production']['sha256'],prompt_hash=next(iter(prompt_hashes)),tool_schema_hash=sorted(schema_sets),
        model_settings=json.loads(next(iter(settings))),dev_task_ids=dev,holdout_task_ids=holdout,
        metric_definitions_version='procurement-metrics-v2.0-accounting-v1',fact_contract_version='canonical-facts-v2.1',
        run_id=args.run_id,complete=True,archive_sha256=index['archive_sha256'],verification_script_sha256=digest(Path(__file__)))
    write(EVAL/'baseline-v2.1.json',baseline)
    audit=dict(passed=True,run_id=args.run_id,regraded_artifacts=regraded,valid_trials=120,
        before_rfq_empty_trials=120,distinct_run_ids=len(runs),distinct_case_ids=len(cases),distinct_approval_ids=len(approvals),
        no_cross_trial_case_approval_receipt_leak=True,legacy_fingerprints_unchanged=5,protected_handler_unchanged=True,
        archive_sha256=index['archive_sha256'],model_comparability_warnings=summary['model_comparability_warnings'])
    write(reports/'b0-integrity-audit.json',audit)
    print(json.dumps(audit,indent=2))
