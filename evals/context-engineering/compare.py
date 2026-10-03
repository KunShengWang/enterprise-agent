"""Offline paired Dev accounting. No model calls, task selection changes, or grading changes."""
import argparse
import collections
import gzip
import json
from pathlib import Path
import sys
import zipfile
from composition import inspect, ROOT

sys.path.insert(0,str(ROOT/'evals/procurement-v2/runners'))
from run import read, write
from reporting import summarize

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    folder=ROOT/'target/harness-core'/args.run_id
    attempts=read(folder/'attempts.json');selection=read(folder/'selection.json')
    current=[r for r in attempts if r['status']!='EVAL_INFRA_ERROR']
    ids={r['trial_id'] for r in current}
    if len(current)!=28 or len(ids)!=28 or set(selection['tasks'])!={r['task_id'] for r in current}:
        raise RuntimeError('Comparison requires complete unique Dev 28x1')
    b0=[json.loads(line) for line in (ROOT/'evals/procurement-v2.2/reports/trials.jsonl').read_text(encoding='utf-8-sig').splitlines()]
    b0=[r for r in b0 if r['trial_id'] in ids]
    with zipfile.ZipFile(ROOT/'evals/harness-core/reports/dev-evidence.zip') as archive:
        phase3=[json.loads(archive.read(n)) for n in archive.namelist() if n.endswith('/result.json')]
    phase3=[r for r in phase3 if r.get('trial_id') in ids and r['status']!='EVAL_INFRA_ERROR']
    if len(b0)!=28 or len(phase3)!=28 or any(r['split']!='dev' for r in current+b0+phase3):
        raise RuntimeError('Unpaired trials or holdout detected')
    mismatch=[]
    for label,reference in [('B0',b0),('Phase3',phase3)]:
        for row in current:
            prior=next(r for r in reference if r['trial_id']==row['trial_id'])
            for key in ('profile_prompt_sha256','schema_sha256','model_options','max_tool_execution_attempts'):
                if row['controls'][key]!=prior['controls'][key]:mismatch.append(dict(source=label,trial=row['trial_id'],control=key))
    context_totals=collections.Counter();compositions={};peaks={};tool_counts=set()
    for row in current:
        artifact=json.loads(gzip.decompress((ROOT/row['artifact_directory']/'artifact.json.gz').read_bytes()))
        raw=artifact['raw'];compositions[row['task_id']]=inspect(raw)
        events=[e['payload'] for e in raw['events'] if e['type'] in ('CONTEXT_PREPARED','CONTEXT_COMPACTED')]
        for event in events:
            for key in ('conversationMessagesRemoved','duplicateToolPairsRemoved','rawToolResultBytes','projectedToolResultBytes','rawToolResultEstimatedTokens','projectedToolResultEstimatedTokens','contextTokensBefore','contextTokensAfter'):
                context_totals[key]+=event.get(key,0)
            context_totals['compactionEvents']+=bool(event.get('compactionPerformed'))
        peaks[row['task_id']]=max((e.get('estimatedWorkingContextTokens',0) for e in events),default=0)
        tool_counts.update(len(m['request']['tools']) for m in raw['models'])
    result=dict(type='single-trial development directional comparison, not final benchmark',
        comparison_gate='COMPARABLE_FOR_DEVELOPMENT' if not mismatch else 'NOT_COMPARABLE',control_mismatches=mismatch,
        caveat='Context composition is the intended variable. Model alias has no immutable build; tool trajectories can vary. No stable causal efficiency claim.',
        b0=summarize(b0,28,1),phase3=summarize(phase3,28,1),phase4=summarize(attempts,28,1),
        runtime=dict(stop_reasons=dict(collections.Counter(r['runtime_metrics']['stop_reason'] for r in current))),
        context=dict(accounting='estimated local partitions, excludes provider framing; bytes/tokens count repeated per-turn projections',
            totals=dict(context_totals),estimated_peak_per_task=peaks,observed_tool_counts=sorted(tool_counts)),
        composition=compositions)
    write(args.output,result)
    print(json.dumps(dict(gate=result['comparison_gate'],passed=result['phase4']['passed'],control_mismatches=mismatch),ensure_ascii=False))

if __name__=='__main__':main()
