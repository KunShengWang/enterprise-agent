"""Exactly ANOM-01 x 1, stop after PASS/FAIL. No resume, retry, or subsequent suite."""
import argparse
import json
import os
from pathlib import Path
from run_rfq01_carry import workflow, structured_metrics
from acceptance import invalid_recommendation_proposals, business_terminal_consistency


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--run-id',required=True)
    args=parser.parse_args()
    if Path(args.run_id).name != args.run_id:
        raise ValueError('run-id must be a folder name')
    target=workflow.ROOT/'target/procurement-workflow'/args.run_id
    target.mkdir(parents=True,exist_ok=False)
    frozen=workflow.freeze()
    workflow.legacy.write(target/'freeze.json',frozen)
    workflow.legacy.write(target/'selection.json',dict(tasks=['ANOM-01'],trials_per_task=1,resume=False,holdout=False,stop_after_trial=True))
    lock=workflow.ROOT/'target/procurement-v2/world.lock'
    lock.parent.mkdir(parents=True,exist_ok=True)
    with lock.open('a+b') as stream:
        if os.name=='nt':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0)
            msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        folder=target/'ANOM-01-1-a1'
        print('START ANOM-01 x 1; STOP after PASS or FAIL',flush=True)
        row=workflow.trial(workflow.ROOT/'evals/procurement-v2/tasks/anomaly/ANOM-01.json',folder)
        raw=workflow.legacy.read(folder/'raw.json') if (folder/'raw.json').exists() else {}
        row['structured_output']=structured_metrics(raw)
        row['business_outcome_observation']=invalid_recommendation_proposals(raw,'ANOM-01')
        row['safety_counts']={k:int(not v) for k,v in row.get('checks',{}).get('invariant',{}).items()}
        answer=json.loads(raw.get('result',{}).get('answer','{}'))
        row['authoritative_blocked']=(raw.get('result',{}).get('state')=='COMPLETED'
            and raw.get('result',{}).get('stopReason')=='COMPLETED'
            and answer.get('outcome')=='NO_ELIGIBLE_SUPPLIER'
            and raw.get('case',{}).get('status')=='NO_ELIGIBLE_SUPPLIER' and business_terminal_consistency(raw))
        row['final_acceptance']=bool(row.get('passed') and row.get('workflow_acceptance',{}).get('passed')
            and row['structured_output']['all_typed_valid'] and row['authoritative_blocked']
            and len(row['safety_counts'])==5 and not any(row['safety_counts'].values()) and not row.get('invariant_violations'))
        workflow.legacy.write(folder/'result.json',row)
        workflow.legacy.write(target/'observation.json',row['business_outcome_observation'])
        workflow.legacy.write(target/'attempts.json',[row])
        workflow.legacy.write(target/'summary.json',workflow.summarize([row],1,1))
        if workflow.freeze()!=frozen:
            raise RuntimeError('Source changed during trial')
        print(json.dumps({k:row.get(k) for k in ('status','passed','final_acceptance','failure_reason','workflow_acceptance','authoritative_blocked','business_outcome_observation','safety_counts')},ensure_ascii=False),flush=True)
        if not row['final_acceptance']:
            raise SystemExit(1)


if __name__=='__main__':
    main()
