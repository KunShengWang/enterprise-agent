"""Offline composition estimates; never model calls. Same character estimator as Runtime."""
import json, gzip, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def estimate(s):
    if not s or not s.strip(): return 0
    ascii_count=sum(ord(c)<=127 for c in s)
    return max(1,(ascii_count+3)//4+sum(1 if ord(c)<=65535 else 2 for c in s if ord(c)>127))
def encoded(x):return json.dumps(x,ensure_ascii=False,separators=(',',':'))
def composition(request):
    totals=dict(instructions=estimate(request['systemPrompt']),tool_schema=sum(estimate(t['name'])+estimate(t['description'])+estimate(t['inputSchema']) for t in request['tools']),business_state=0,run_state=0,conversation=0,tool_result=0,other=0)
    for m in request['messages']:
        kind=m['type'];key='conversation'
        if kind=='CANONICAL_CONTEXT':key='run_state' if m.get('metadata',{}).get('contextKind')=='run_state' else 'business_state'
        elif kind=='TOOL_RESULT':key='tool_result'
        elif kind=='ASSISTANT_TOOL_CALL':key='other'
        elif kind=='SYSTEM':key='instructions'
        text=m['content']
        if kind=='TOOL_RESULT':text=encoded(dict(success=m['metadata'].get('success'),content=text,error=m['metadata'].get('error',''),metadata=m['metadata']))
        if kind=='ASSISTANT_TOOL_CALL':text=encoded(m['arguments'])+m['toolName']
        totals[key]+=estimate(text)
    totals['total_estimated']=sum(totals.values());return totals

def inspect(raw):
    return dict(turns=[dict(turn=i+1,**composition(m['request'])) for i,m in enumerate(raw['models'])],provider_model_prompt_tokens=sum(e['payload'].get('promptTokens',0) for e in raw['events'] if e['type']=='MODEL_COMPLETED' and e['payload'].get('usageSource')=='provider'),provider_model_output_tokens=sum(e['payload'].get('completionTokens',0) for e in raw['events'] if e['type']=='MODEL_COMPLETED' and e['payload'].get('usageSource')=='provider'))
if __name__=='__main__':
    ids=['REQ-03','SRC-01','RFQ-02','E2E-04'];out={'estimate_method':'ConservativeTokenEstimator: ceil(ASCII/4)+non-ASCII UTF16 units; excludes provider wrappers/framing; not provider usage','selected_tasks':ids,'sources':{}}
    for label,file in [('Harness B0','evals/procurement-contracts/reports/business-stabilized-evidence.zip'),('Phase 3 Dev','evals/harness-core/reports/dev-evidence.zip')]:
        with zipfile.ZipFile(ROOT/file) as z:
            out['sources'][label]={tid:inspect(json.loads(gzip.decompress(z.read(next(n for n in z.namelist() if tid+'-1-a1/' in n and n.endswith('artifact.json.gz')))))['raw']) for tid in ids}
    (Path(__file__).parent/'reports/pre-change-context-audit.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for label,rows in out['sources'].items():
        for tid,row in rows.items():
            sums={k:sum(t[k] for t in row['turns']) for k in row['turns'][0] if k!='turn'}
            print(label,tid,'turns',len(row['turns']),sums,'provider input',row['provider_model_prompt_tokens'])
