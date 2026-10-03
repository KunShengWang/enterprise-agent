"""Offline paired trial-1 Dev comparison. Never selects/reruns a model trial."""
import argparse
import collections
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'evals/procurement-v2/runners'))
from run import read, write
from reporting import summarize

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();folder=ROOT/'target/harness-core'/args.run_id
    selection=read(folder/'selection.json');attempts=read(folder/'attempts.json')
    current=[r for r in attempts if r['status']!='EVAL_INFRA_ERROR']
    if len(current)!=len(selection['tasks']) or len({r['trial_id'] for r in current})!=len(current):
        raise RuntimeError('Incomplete or duplicate valid trials; no comparison')
    all_b0=[json.loads(line) for line in (ROOT/'evals/procurement-v2.2/reports/trials.jsonl').read_text(encoding='utf-8-sig').splitlines()]
    b0=[r for r in all_b0 if r['trial_id'] in {r['trial_id'] for r in current}]
    if len(b0)!=len(current) or any(r['split']!='dev' for r in b0+current): raise RuntimeError('Unpaired or holdout trial')
    mismatches=[]
    for row in current:
        reference=next(r for r in b0 if r['trial_id']==row['trial_id'])
        for key in ('profile_prompt_sha256','schema_sha256','model_options','max_tool_execution_attempts'):
            if row['controls'][key]!=reference['controls'][key]:mismatches.append(dict(trial=row['trial_id'],control=key))
    before=summarize(b0,len(b0),1);after=summarize(attempts,len(current),1)
    outcomes=collections.Counter()
    for row in current:outcomes.update(row['runtime_metrics']['outcome_counts'])
    result=dict(type='development directional comparison; not final resume benchmark',
                baseline_commit='ad563544fce6ae2bc238d555c6bf70abc1f38a4a',baseline_tag='procurement-harness-b0',
                pairing='same Dev task IDs, frozen trial 1, one current valid trial',
                control_mismatches=mismatches,comparison_gate='COMPARABLE_FOR_DEVELOPMENT' if not mismatches else 'NOT_COMPARABLE',
                model_limitation='Provider immutable build unavailable; alias equality does not imply identical weights.',
                b0=before,current=after,
                runtime=dict(stop_reason_counts=dict(collections.Counter(r['runtime_metrics']['stop_reason'] for r in current)),
                             outcome_counts=dict(outcomes),budget_exhaustion_count=sum(r['runtime_metrics']['budget_exhausted'] for r in current)),
                pairs=[dict(trial=r['trial_id'],status=r['status'],b0_status=next(b['status'] for b in b0 if b['trial_id']==r['trial_id'])) for r in current])
    write(args.output,result)
    print(json.dumps(dict(gate=result['comparison_gate'],trials=len(current),b0_pass=before['passed'],current_pass=after['passed'],controls=mismatches),ensure_ascii=False))

if __name__=='__main__':main()
