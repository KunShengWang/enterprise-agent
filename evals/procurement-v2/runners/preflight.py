"""Read oracle prerequisites in each distinct real ERP setup, before freezing B0."""
import collections
from run import ROOT,EVAL,read,write,world,canonical_hash
from grade import eligible

if __name__=='__main__':
    target=ROOT/'target/procurement-v2/world-preflight';target.mkdir(parents=True,exist_ok=True)
    grouped=collections.defaultdict(list)
    for p in sorted((EVAL/'tasks').rglob('*.json')):grouped[canonical_hash(read(p)['world']['setup'])].append(p)
    rows=[]
    for i,paths in enumerate(grouped.values()):
        folder=target/str(i);folder.mkdir(exist_ok=True)
        state=world(paths[0],folder,'reset')
        contract=read(EVAL/'fixtures/world.json')
        assert state['baseBusinessHash']==contract['base_business_sha256'] and state['counts']==contract['counts']
        assert not state['rfqs']
        for path in paths:
            task=read(path);ids=sorted(eligible(task,state));exp=task['expected']
            if exp['outcome'] in ('recommend','rfq'):assert ids,task['task_id']
            if exp['unique_supplier']:assert ids==[exp['unique_supplier']],task['task_id']
            if task['task_id'].startswith('ANOM-') and task['task_id']!='ANOM-03':assert not ids,task['task_id']
            if task['task_id']=='ANOM-03':
                duplicates=[r for r in state['facts']['sample_requisitions'] if r['custom_department_id']=='V2-ALG' and r['items'][0]['item_code']=='V2-WS-001' and r['items'][0]['qty']==20]
                assert len(duplicates)==2
            rows.append(dict(task_id=task['task_id'],eligible=ids,business_hash=state['businessHash']))
        print('validated setup',i,'tasks',','.join(read(p)['task_id'] for p in paths),flush=True)
    write(target/'results.json',dict(passed=True,tasks=len(rows),distinct_worlds=len(grouped),rows=rows))
