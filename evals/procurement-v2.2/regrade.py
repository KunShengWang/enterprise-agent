"""Offline-only, hash-bound V2.2 regrade. No world reset, model or ERP requests."""
import collections
import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = ROOT / 'evals/procurement-contracts/reports'
LEGACY = ROOT / 'evals/procurement-v2'
sys.path.insert(0, str(LEGACY / 'runners'))
from run import manifest, digest, schema_hash, read, write
from reporting import summarize
from grade_v22 import grade, grade_v21
from canonical_state import SCHEMAS, CHILDREN, project, business_hash, stable_bytes

def differences(before, after, path=''):
    result = []
    if isinstance(before, dict) and isinstance(after, dict):
        for k in sorted(before.keys() | after.keys()):
            if k not in before or k not in after:
                result.append(dict(path=path+'/'+k, before=before.get(k), after=after.get(k),
                                   before_present=k in before, after_present=k in after))
            else: result.extend(differences(before[k], after[k], path+'/'+k))
    elif isinstance(before, list) and isinstance(after, list):
        if len(before) != len(after): result.append(dict(path=path+'/length', before=len(before), after=len(after)))
        for i, (a, b) in enumerate(zip(before, after)): result.extend(differences(a, b, path+'/'+str(i)))
    elif before != after: result.append(dict(path=path, before=before, after=after))
    return result

def main():
    initial = read(HERE/'reports/audit-start.json')
    assert all(digest(ROOT/p)==h for p,h in initial['files'].items()), 'pre-existing source/evidence changed'
    old = read(SOURCE/'business-stabilized-summary.json'); freeze = old['freeze']
    now = manifest(); assert all(now[k]==v for k,v in freeze.items() if k!='experiment'), 'production/control drift'
    assert all(digest(ROOT/p)==h for p,h in freeze['experiment']['frozen_eval_file_bytes'].items()), 'V2.1 changed'
    reference = read(LEGACY/'baseline-v2.1.json')
    rows = [json.loads(line) for line in (SOURCE/'business-stabilized-trials.jsonl').read_text(encoding='utf-8').splitlines()]
    index = read(SOURCE/'business-stabilized-evidence-index.json')
    archive = SOURCE/'business-stabilized-evidence.zip'
    assert digest(archive)==index['archive_sha256']
    indexed = {e['path']:e['sha256'] for e in index['artifacts']}
    valid = [r for r in rows if r['status']!='EVAL_INFRA_ERROR']
    assert len(valid)==len({r['trial_id'] for r in valid})==120
    assert len({r['task_id'] for r in valid})==40
    assert set(collections.Counter(r['task_id'] for r in valid).values())=={3}
    tasks = {read(p)['task_id']:read(p) for p in (LEGACY/'tasks').rglob('*.json')}
    assert {r['trial_id'] for r in valid}=={f'{t}-{n}' for t in tasks for n in (1,2,3)}
    by_folder = {Path(r['artifact_directory']).name:r for r in rows}
    outcomes = {}; audit = []; diffs = []; robustness = []; changed_checks = []
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for path, sha in indexed.items():
            payload = z.read(path); assert hashlib.sha256(payload).hexdigest()==sha
            a = json.loads(gzip.decompress(payload)); row = by_folder[Path(path).parent.name]
            assert row['artifact_sha256']==sha and a['task']==tasks[row['task_id']]
            v21 = grade_v21(a['task'], a['raw'], a['before'], a['after'])
            assert all(row[k]==v for k,v in v21.items()), ('original grade mismatch', row['trial_id'])
            if row['status']=='EVAL_INFRA_ERROR': continue
            for snapshot in (a['before'], a['after']):
                raw_hash=hashlib.sha256(json.dumps(snapshot['facts'],sort_keys=True,default=str,
                    ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
                assert raw_hash==snapshot['businessHash'], 'stored V2.1 business hash not reproducible'
            control = row['controls']; raw = a['raw']
            assert control['profile_prompt_sha256']==reference['prompt_hash']==raw['controls']['profilePromptSha256']
            assert control['model_options']==reference['model_settings']==raw['controls']['effectiveModelOptions']
            schemas = sorted({schema_hash(m['request']['tools']) for m in raw['models']})
            assert schemas==control['schema_sha256'] and set(schemas)<=set(reference['tool_schema_hash'])
            assert row['resolved_models']==reference['resolved_models']
            v22 = grade(a['task'], raw, a['before'], a['after'])
            # Hash checks may change; all other checks and raw efficiency measurements must match.
            for layer in ('state','trace','structured'): assert v22['checks'][layer]==v21['checks'][layer]
            for k in old['metrics']: assert v22[k]==row[k]
            for k in ('tool_errors','tool_error_categories','invalid_tool_calls','tool_error_rate'):
                assert v22[k]==row[k]
            for key in v21['checks']['invariant']:
                if key!='BusinessInvariantViolation': assert v22['checks']['invariant'][key]==v21['checks']['invariant'][key]
            updated = dict(row, **v22); outcomes[row['trial_id']] = updated
            if v22['status']!=v21['status']:
                changed_checks.append(dict(trial_id=row['trial_id'],before=v21['status'],after=v22['status'],
                                           before_reason=v21['failure_reason'],after_reason=v22['failure_reason']))
            audit.append(dict(trial_id=row['trial_id'],task_id=row['task_id'],artifact_sha256=sha,
                              prompt_hash=control['profile_prompt_sha256'],tool_schema_hash=schemas,
                              run_id=raw['result']['runId'],before_hashes=v22['canonical_state']['before'],
                              after_hashes=v22['canonical_state']['after']))
            if row['group']=='robustness' or row['task_id'].startswith('ROB-'):
                robustness.append(dict(trial_id=row['trial_id'],status=v22['status'],tool_errors=v22['tool_errors'],
                                       tool_error_categories=v22['tool_error_categories']))
            if row['trial_id'] in ('E2E-04-1','ROB-03-1'):
                changes = differences(a['before'], a['after'])
                facts = differences(a['before']['facts'], a['after']['facts'], '/facts')
                for d in facts:
                    parts=d['path'].split('/'); entity=a['before']['facts'][parts[2]][int(parts[3])]
                    d.update(entity_id=entity['name'],entity_type=entity['doctype'])
                # Stop, rather than weaken grading, if either investigated trial changed business data.
                assert business_hash(a['before'])==business_hash(a['after']), 'real business change in investigated trial'
                assert all(d['path'].count('/')==4 and d['path'].endswith('/idx') for d in facts), 'unexpected changed field'
                diffs.append(dict(trial_id=row['trial_id'],all_snapshot_differences=changes,entity_field_differences=facts,
                                  canonical_before=project(a['before']),canonical_after=project(a['after']),
                                  versions_unchanged=a['before']['versions']==a['after']['versions'],rfq_count=len(a['after']['rfqs'])))
    assert len(outcomes)==120
    new_rows = [outcomes[r['trial_id']] if r['status']!='EVAL_INFRA_ERROR' else copy.deepcopy(r) for r in rows]
    result = summarize(new_rows,40,3)
    for k in ('metrics','tool_attempts','tool_errors','tool_error_categories','PreventedUnsafeAttempt','human_rejected_proposals'):
        assert result[k]==old[k], ('non-grading metric drift',k)
    result.update(eval_version='procurement-eval-v2.2',evaluation_mode='offline_regrade_existing_artifacts',
                  source_run_id=old['run_id'],model_calls_during_regrade=0,changed_outcomes=changed_checks,
                  robustness_trials=robustness,production_code_sha=freeze['code_sha'],
                  production_working_tree_hash=freeze['production']['sha256'],production_uncommitted=True,
                  source_archive_sha256=index['archive_sha256'])
    report_dir=HERE/'reports';write(report_dir/'summary.json',result)
    (report_dir/'trials.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in new_rows),encoding='utf-8',newline='\n')
    write(report_dir/'field-level-audit.json',diffs)
    write(report_dir/'artifact-provenance.json',dict(passed=True,valid_artifacts=120,complete_artifacts_verified=len(indexed),
          task_count=40,source_archive_sha256=index['archive_sha256'],source_production_manifest=freeze['production'],
          source_code_sha=freeze['code_sha'],working_tree_dirty=True,profile_and_tool_schemas_verified=True,
          input_evidence_unchanged=True,all_other_grader_checks_unchanged=True,trials=audit))
    write(report_dir/'projection-contract.json',dict(version='canonical-business-state-v2.2',fields=SCHEMAS,ordered_children=CHILDREN))
    baseline_world=next(d['canonical_before'] for d in diffs if d['trial_id']=='E2E-04-1')
    write(report_dir/'world-contract.json',dict(contract='canonical-business-state-v2.2',source_trial='E2E-04-1',
        canonical_business_sha256=hashlib.sha256(stable_bytes(baseline_world)).hexdigest(),
        role='Default seeded business world; future preflight must use this projection, not the V2.1 raw-document hash.',
        projection=baseline_world))
    assert all(digest(ROOT/p)==h for p,h in initial['files'].items()), 'original files modified during offline regrade'
    print(json.dumps({k:result[k] for k in ('passed','failed','valid_trials','infra_attempts','three_of_three_consistent_success_count','invariant_counts','changed_outcomes')}))

if __name__=='__main__': main()
