"""Phase 2.5 experiment output isolation; delegates every trial and metric to frozen V2.1.

No Agent orchestration, task/grader/model overrides, or edits to the frozen B0 reports.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[2]
EVAL=ROOT/'evals/procurement-v2'
sys.path.insert(0,str(EVAL/'runners'))
from run import run_trial,manifest,read,write,digest
from reporting import summarize,link_attempts

def baseline_files():
    return {p.relative_to(ROOT).as_posix():digest(p) for p in sorted(EVAL.rglob('*'))
            if p.is_file() and '__pycache__' not in p.parts}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--resume',action='store_true')
    args=parser.parse_args()
    target=ROOT/'target/procurement-v2'/args.run_id;target.mkdir(parents=True,exist_ok=True)
    before=baseline_files();frozen=manifest();reference=read(EVAL/'fixtures/freeze-v2.1.json')
    for group in ('tasks','seed','world_contract','model_reference','graders','runner'):
        assert frozen[group]==reference[group],f'frozen V2.1 {group} changed'
    frozen['experiment']=dict(name='Business-Stabilized Single-Agent Run',implementation_commit=None,
        working_tree_dirty=True,baseline_tag='procurement-eval-v2.1',output_wrapper_sha256=digest(Path(__file__)),
        frozen_eval_file_bytes=before)
    freeze_path=target/'freeze.json'
    if freeze_path.exists():
        assert args.resume and read(freeze_path)==frozen,'experiment source drift or resume missing'
    else:write(freeze_path,frozen)
    lock=ROOT/'target/procurement-v2/world.lock'
    with lock.open('a+b') as stream:
        if os.name=='nt':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        tasks=sorted((EVAL/'tasks').rglob('*.json'));assert len(tasks)==40
        attempts=[]
        for task in tasks:
            tid=read(task)['task_id']
            for repeat in range(1,4):
                trial=f'{tid}-{repeat}'
                previous=sorted(target.glob(trial+'-a*/result.json'),key=lambda p:int(p.parent.name.rsplit('-a',1)[1]))
                existing=[read(p) for p in previous];attempts.extend(existing)
                if existing and existing[-1]['status']!='EVAL_INFRA_ERROR':continue
                for number in range(len(existing)+1,len(existing)+3):
                    folder=target/f'{trial}-a{number}';print('START '+folder.name,flush=True)
                    result=run_trial(task,trial,folder);attempts.append(result)
                    print(json.dumps({k:result.get(k) for k in ('trial_id','status','failure_category','failure_reason')},ensure_ascii=False),flush=True)
                    with (target/'attempts.jsonl').open('a',encoding='utf-8') as journal:journal.write(json.dumps(result,ensure_ascii=False)+'\n')
                    write(target/'summary.json',summarize(attempts,40,3))
                    if result['status']!='EVAL_INFRA_ERROR':break
                if result['status']=='EVAL_INFRA_ERROR':raise RuntimeError('Repeated infrastructure failure; preserve evidence and diagnose before resume')
        assert before==baseline_files(),'frozen B0 changed'
        current=manifest();assert all(current[k]==v for k,v in frozen.items() if k!='experiment'),'production/controls changed during run'
        reports=ROOT/'evals/procurement-contracts/reports';reports.mkdir(parents=True,exist_ok=True)
        summary=dict(summarize(attempts,40,3),run_id=args.run_id,experiment=frozen['experiment'],freeze=frozen)
        write(reports/'business-stabilized-summary.json',summary)
        (reports/'business-stabilized-trials.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in link_attempts(attempts)),encoding='utf-8',newline='\n')
        artifacts=[]
        with zipfile.ZipFile(reports/'business-stabilized-evidence.zip','w',zipfile.ZIP_STORED) as bundle:
            for path in sorted(target.glob('*-a*/artifact.json.gz')):
                name=path.parent.name+'/'+path.name;bundle.write(path,name)
                artifacts.append(dict(path=name,sha256=digest(path)))
            for name in ('freeze.json','summary.json','attempts.jsonl'):bundle.write(target/name,name)
        write(reports/'business-stabilized-evidence-index.json',dict(run_id=args.run_id,artifacts=artifacts,
            archive_sha256=digest(reports/'business-stabilized-evidence.zip')))
        print('COMPLETE '+json.dumps({k:summary[k] for k in ('passed','failed','valid_trials','infra_attempts')}) ,flush=True)

if __name__=='__main__':main()
