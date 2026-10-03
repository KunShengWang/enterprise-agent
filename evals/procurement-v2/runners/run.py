"""Sequential, restartable real-model benchmark. No Agent policy or benchmark selection at runtime."""
import argparse
import collections
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[3]
EVAL=ROOT/'evals/procurement-v2'
sys.path.insert(0,str(EVAL/'graders'))
from grade import grade
from reporting import summarize, enrich_attempt, link_attempts

def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def write(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def canonical_hash(data): return hashlib.sha256(json.dumps(data,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()

def schema_hash(definitions):
    # ToolDefinition.inputSchema is a JSON string. Ignore map/required order, preserve descriptions and all values.
    def normalize(value,key=''):
        if key=='inputSchema' and isinstance(value,str):return normalize(json.loads(value))
        if isinstance(value,dict):return {k:normalize(v,k) for k,v in value.items()}
        if isinstance(value,list):
            result=[normalize(v) for v in value]
            return sorted(result,key=lambda x:json.dumps(x,sort_keys=True)) if key in ('required','enum','requiredRoles') else result
        return value
    return canonical_hash(normalize(sorted(definitions,key=lambda t:t['name'])))

def manifest():
    groups={
        'tasks': sorted((EVAL/'tasks').rglob('*.json')),
        'seed': sorted((ROOT/'data/procurement/v2/seeds').glob('*.json')),
        'world_contract': [EVAL/'fixtures/world.json'],
        'model_reference': [EVAL/'fixtures/model-reference.json'],
        'graders': sorted((EVAL/'graders').glob('*.py')),
        'runner': sorted((EVAL/'runners').glob('*.py'))+[ROOT/'src/test/java/com/agent/platform/procurement/ProcurementV2TrialDriver.java'],
        'production': sorted((ROOT/'src/main').rglob('*.java'))+sorted((ROOT/'src/main/resources').rglob('*.yaml')),
    }
    result={'version':'procurement-eval-v2.1','code_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()}
    for name,paths in groups.items():
        entries={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in paths}
        result[name]={'sha256':canonical_hash(entries),'files':entries}
    return result

def command(args,log,timeout=180,env=None):
    with Path(log).open('w',encoding='utf-8') as stream:
        result=subprocess.run(args,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout,env=env)
    if result.returncode: raise RuntimeError(f'command exit {result.returncode}; see {log}')

def world(task,directory,phase):
    mount=ROOT/'target/erpnext';mount.mkdir(exist_ok=True,parents=True)
    shutil.copyfile(EVAL/'runners/erp_world.py',mount/'eval_world.py')
    shutil.copyfile(task,mount/'eval_task.json')
    args=['docker','compose','--env-file','infra/erpnext/.env','-f','infra/erpnext/compose.yaml','exec','-T','backend',
          '/home/frappe/frappe-bench/env/bin/python','/workspace/output/eval_world.py',phase,
          '/workspace/output/eval_task.json','/workspace/output/eval_state.json']
    command(args,directory/(phase+'.log'))
    state=read(mount/'eval_state.json');write(directory/(phase+'.json'),state);return state

def model_env():
    env=os.environ.copy()
    for line in (ROOT/'target/erpnext/runtime.env').read_text(encoding='utf-8-sig').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key,value=line.split('=',1); env[key]=value
    if not env.get('DEEPSEEK_API_KEY'): raise RuntimeError('DEEPSEEK_API_KEY unavailable')
    return env

def run_trial(taskpath,trial_id,folder):
    folder.mkdir(parents=True,exist_ok=False);task=read(taskpath);start=time.monotonic();raw={}
    try:
        before=world(taskpath,folder,'reset')
        contract=read(EVAL/'fixtures/world.json')
        if before['baseBusinessHash']!=contract['base_business_sha256'] or before['counts']!=contract['counts']:
            raise RuntimeError('reset/reseed differs from frozen Phase-1 business world')
        classpath=str(ROOT/'target/test-classes')+os.pathsep+str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/procurement-v2-classpath.txt').read_text().strip()
        # Java argfile avoids Windows command-line length limits without a shell.
        argsfile=folder/'java.args'
        quote=lambda s:'"'+str(s).replace('\\','/').replace('"','\\"')+'"'
        argsfile.write_text('-cp\n'+quote(classpath)+'\ncom.agent.platform.procurement.ProcurementV2TrialDriver\n'+quote(taskpath)+'\n'+quote(folder/'raw.json')+'\n',encoding='utf-8')
        command(['java','@'+str(argsfile)],folder/'agent.log',timeout=660,env=model_env())
        raw=read(folder/'raw.json');after=world(taskpath,folder,'snapshot')
        result=grade(task,raw,before,after)
        result.update(trial_id=trial_id,trace_id=raw['result']['runId'])
        schemas=[schema_hash(m['request']['tools']) for m in raw['models']]
        result['controls']=dict(profile_prompt_sha256=raw['controls']['profilePromptSha256'],
            schema_sha256=sorted(set(schemas)),model_options=raw['controls']['effectiveModelOptions'],
            max_tool_execution_attempts=raw['controls']['maxToolExecutionAttempts'],
            base_business_hash=before['baseBusinessHash'])
        # Complete trace retained as compressed artifact, separate from compact trial results.
        with gzip.open(folder/'artifact.json.gz','wt',encoding='utf-8') as stream:
            json.dump(dict(task=task,raw=raw,before=before,after=after),stream,ensure_ascii=False,separators=(',',':'))
        result['artifact_sha256']=digest(folder/'artifact.json.gz')
    except Exception as exc:
        result=dict(task_id=task['task_id'],split=task['split'],trial_id=trial_id,status='EVAL_INFRA_ERROR',passed=None,
                    failure_category='EVAL_INFRA_ERROR',failure_reason=[str(exc)],invariant_violations=[],trace_id=None)
    result['wall_clock_seconds']=time.monotonic()-start
    result['artifact_directory']=folder.relative_to(ROOT).as_posix()
    enrich_attempt(result,raw,folder)
    if 'MODEL_COMPARABILITY_WARNING' in result['model_comparability_warnings']:
        print('MODEL_COMPARABILITY_WARNING: '+json.dumps(result['resolved_models']),flush=True)
    write(folder/'result.json',result);return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['smoke','b0','order'],default='smoke')
    parser.add_argument('--tasks',default='SRC-05');parser.add_argument('--run-id',required=True);parser.add_argument('--resume',action='store_true')
    options=parser.parse_args()
    target=ROOT/'target/procurement-v2'/options.run_id; target.mkdir(parents=True,exist_ok=True)
    tasks={read(p)['task_id']:p for p in sorted((EVAL/'tasks').rglob('*.json'))}
    selected=list(tasks) if options.mode=='b0' else options.tasks.split(',')
    if options.mode=='order': selected=[selected[0],selected[1],selected[1],selected[0]]
    freeze=manifest()
    freeze_path=target/'freeze.json'
    if freeze_path.exists():
        if read(freeze_path)!=freeze: raise RuntimeError('Frozen semantics/source changed: use a NEW run-id and rerun ALL B0 trials')
        if not options.resume: raise RuntimeError('Run exists; explicit --resume required')
    else: write(freeze_path,freeze)
    lock=ROOT/'target/procurement-v2/world.lock'
    # OS lock is released after a process crash; lock file itself is not proof of ownership.
    lock.parent.mkdir(exist_ok=True,parents=True)
    with lock.open('a+b') as stream:
        if os.name=='nt':
            import msvcrt
            stream.seek(0);stream.write(b'0');stream.flush();stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        attempts=[]
        for index,tid in enumerate(selected):
            for repeat in range(1,4 if options.mode=='b0' else 2):
                trial=f'{tid}-{repeat}' if options.mode!='order' else f'{tid}-order-{index+1}'
                previous=sorted(target.glob(trial+'-a*/result.json'),key=lambda p:int(p.parent.name.rsplit('-a',1)[1]))
                existing=[read(p) for p in previous];attempts.extend(existing)
                if existing and existing[-1]['status']!='EVAL_INFRA_ERROR':continue
                # Preserve all infra attempts. A repeated outage stops the batch for diagnosis.
                for attempt in range(len(existing)+1,len(existing)+3):
                    folder=target/f'{trial}-a{attempt}'
                    print(f'START {folder.name}',flush=True)
                    result=run_trial(tasks[tid],trial,folder);attempts.append(result)
                    print(json.dumps({k:result.get(k) for k in ['trial_id','status','failure_category','tool_calls','failure_reason']},ensure_ascii=False),flush=True)
                    with (target/'attempts.jsonl').open('a',encoding='utf-8') as out:out.write(json.dumps(result,ensure_ascii=False)+'\n')
                    write(target/'summary.json',summarize(attempts,len(selected),3 if options.mode=='b0' else 1))
                    if result['status']!='EVAL_INFRA_ERROR':break
                if result['status']=='EVAL_INFRA_ERROR':raise RuntimeError('Repeated infrastructure failure: batch stopped, provenance retained; diagnose before --resume')
        write(target/'summary.json',summarize(attempts,len(selected),3 if options.mode=='b0' else 1))
        if options.mode=='order':
            valid=[r for r in attempts if r['status']!='EVAL_INFRA_ERROR']
            pairs={tid:[r for r in valid if r['task_id']==tid] for tid in set(selected)}
            comparisons={tid:len(rows)==2 and rows[0]['checks']==rows[1]['checks'] and
                         rows[0]['erp_state_summary']['business_hash_before']==rows[1]['erp_state_summary']['business_hash_before']
                         for tid,rows in pairs.items()}
            write(target/'order-independence.json',dict(order=selected,equal_grades_and_worlds=comparisons,passed=all(comparisons.values())))
        if options.mode=='b0':
            reports=EVAL/'reports';reports.mkdir(exist_ok=True)
            write(reports/'b0-summary.json',dict(summarize(attempts,len(selected),3),run_id=options.run_id,freeze=freeze,generated_at=datetime.now(timezone.utc).isoformat()))
            (reports/'b0-trials.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in link_attempts(attempts)),encoding='utf-8',newline='\n')

if __name__=='__main__':main()
