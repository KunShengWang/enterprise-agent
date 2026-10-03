"""Offline exposure accounting; does not invoke models or change frozen grade results."""
import argparse,collections,gzip,json,math,statistics,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'evals/procurement-v2/runners'))
from run import read,write,schema_hash
from reporting import summarize
sys.path.insert(0,str(ROOT/'evals/context-engineering'))
from composition import composition

def distribution(values):
    return dict(count=len(values),sum=sum(values),mean=statistics.mean(values) if values else None,
                p95=sorted(values)[math.ceil(len(values)*.95)-1] if values else None)

def exposure(raw):
    turns=[dict(visible_tools=len(m['request']['tools']),schema_estimated_tokens=composition(m['request'])['tool_schema'],
                context_composition=composition(m['request'])) for m in raw['models']]
    events=[e['payload'] for e in raw['events'] if e['type'] in ('CONTEXT_PREPARED','CONTEXT_COMPACTED')]
    hidden=[]
    for e in raw['events']:
        if e['type']=='TOOL_COMPLETED' and 'SELECTOR_HIDDEN_TOOL' in json.dumps(e['payload']):hidden.append(e['payload'])
    # Diagnostic proxy only: repeated successful MCP read requests can be intentional refresh/recovery.
    seen=set();repeated=0
    for e in raw.get('executions',[]):
        if e['state']=='SUCCEEDED' and e['toolName'].startswith('mcp.erpnext.procurement_') and e['toolName']!='mcp.erpnext.procurement_create_rfq':
            key=(e['toolName'],json.dumps(e['request']['arguments'],sort_keys=True))
            repeated+=key in seen;seen.add(key)
    return dict(turns=turns,selection_events=events,hidden_tool_attempts=hidden,repeated_successful_read_request_proxy=repeated)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();folder=ROOT/'target/harness-core'/args.run_id
    attempts=read(folder/'attempts.json');current=[r for r in attempts if r['status']!='EVAL_INFRA_ERROR']
    selection=read(folder/'selection.json');ids={r['trial_id'] for r in current}
    if len(current)!=len(selection['tasks']) or len(current)!=len(ids):raise RuntimeError('Incomplete/duplicate valid trials')
    archive=zipfile.ZipFile(ROOT/'evals/context-engineering/reports/dev-evidence.zip')
    oldrows=[json.loads(archive.read(n)) for n in archive.namelist() if n.endswith('/result.json')]
    oldrows=[r for r in oldrows if r.get('trial_id') in ids and r['status']!='EVAL_INFRA_ERROR']
    assert len(oldrows)==len(current) and all(r['split']=='dev' for r in current+oldrows)
    oldraw={r['trial_id']:json.loads(gzip.decompress(archive.read(next(n for n in archive.namelist() if r['trial_id']+'-a1/' in n and n.endswith('artifact.json.gz')))))['raw'] for r in oldrows}
    mismatch=[];details={};all_old=[];all_new=[];stages=collections.Counter();candidate=[];selected=[]
    for row in current:
        prior=next(r for r in oldrows if r['trial_id']==row['trial_id'])
        for key in ('profile_prompt_sha256','model_options','max_tool_execution_attempts'):
            if row['controls'][key]!=prior['controls'][key]:mismatch.append([row['trial_id'],key])
        for key in ('configured_model','resolved_models'):
            if row.get(key)!=prior.get(key):mismatch.append([row['trial_id'],key])
        raw=json.loads(gzip.decompress((ROOT/row['artifact_directory']/'artifact.json.gz').read_bytes()))['raw']
        before=oldraw[row['trial_id']]
        definitions={t['name']:schema_hash([t]) for m in before['models'] for t in m['request']['tools']}
        for m in raw['models']:
            for tool in m['request']['tools']:
                if definitions.get(tool['name'])!=schema_hash([tool]):mismatch.append([row['trial_id'],'definition_changed',tool['name']])
        a,b=exposure(before),exposure(raw);all_old+=a['turns'];all_new+=b['turns']
        for e in b['selection_events']:
            if 'selection_stage' in e:
                stages[e['selection_stage']]+=1;candidate.append(e['candidate_schema_estimated_tokens']);selected.append(e['tool_schema_estimated_tokens'])
        details[row['task_id']]=dict(phase4=a,phase5=b,status=row['status'],
            selection_failure_diagnostic='TOOL_SELECTION_MISS_CANDIDATE_REQUIRES_TRACE_REVIEW' if row['status']!='PASS' and b['hidden_tool_attempts'] else None)
    totals=lambda rows:{key:distribution([t[key] for t in rows]) for key in ('visible_tools','schema_estimated_tokens')}
    report=dict(comparison_gate='COMPARABLE_FOR_DEVELOPMENT' if not mismatch else 'NOT_COMPARABLE',control_mismatches=mismatch,
        controls='Same task/trial 1, prompt, model options, retries and unchanged per-tool contracts. Schema membership is the intended variable.',
        limitation='Single Dev trial; immutable provider build unavailable. Overall usage/latency and planning paths are directional, not causal efficiency proof.',
        phase4=summarize(oldrows,len(current),1),phase5=summarize(attempts,len(current),1),
        schema_accounting='ConservativeTokenEstimator local estimates, excludes provider framing. Different trajectories change aggregate weights.',
        phase4_exposure=totals(all_old),phase5_exposure=totals(all_new),
        same_turn_schema=dict(candidate=distribution(candidate),selected=distribution(selected),
            explanation='Before/after selection on the same current turn; most direct schema exposure effect.'),
        stages=dict(stages),stop_reasons=dict(collections.Counter(r['runtime_metrics']['stop_reason'] for r in current)),
        tool_selection_hidden_attempts=sum(len(d['phase5']['hidden_tool_attempts']) for d in details.values()),
        unnecessary_tool_calls=None,unnecessary_limitation='No independent semantic unnecessary-call grader. Repeated read request proxy is diagnostic, not necessarily unnecessary.',
        repeated_read_proxy={label:sum(d[label]['repeated_successful_read_request_proxy'] for d in details.values()) for label in ('phase4','phase5')},details=details)
    write(args.output,report);print(json.dumps({k:report[k] for k in ('comparison_gate','control_mismatches','stages','stop_reasons','tool_selection_hidden_attempts')}))

if __name__=='__main__':main()
