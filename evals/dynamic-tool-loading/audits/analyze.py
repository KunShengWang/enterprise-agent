"""Offline only: read frozen ZIPs; no runner import, network, subprocess or model invocation."""
import collections
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).parent
sys.path.insert(0, str(ROOT/'evals/context-engineering'))
from composition import composition, estimate

def encoded(v):
    return json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def digest(b):
    return hashlib.sha256(b).hexdigest()

def write(name, value):
    (OUT/name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')

def schema_hash(tools):
    def normalize(v, key=''):
        if key == 'inputSchema' and isinstance(v, str): return normalize(json.loads(v))
        if isinstance(v, dict): return {k: normalize(x, k) for k, x in v.items()}
        if isinstance(v, list):
            vals = [normalize(x) for x in v]
            return sorted(vals, key=encoded) if key in ('required', 'enum', 'requiredRoles') else vals
        return v
    return digest(encoded(normalize(sorted(tools, key=lambda t: t['name']))).encode())

def load(phase, folder):
    path = ROOT/'evals'/folder/'reports/dev-evidence.zip'
    prov = json.loads(path.with_name('dev-provenance.json').read_text(encoding='utf-8'))
    assert digest(path.read_bytes()) == prov['sha256']
    rows = {}; infra = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            assert digest(z.read(name)) == prov['files'][name], name
            if not name.endswith('/result.json'): continue
            row = json.loads(z.read(name))
            if row['status'] == 'EVAL_INFRA_ERROR':
                infra.append(row); continue
            tid = row['task_id']
            assert tid not in rows and row['split'] == 'dev' and row['trial_id'] == tid+'-1'
            artifact = name.rsplit('/', 1)[0]+'/artifact.json.gz'
            data = z.read(artifact)
            assert digest(data) == row['artifact_sha256']
            rows[tid] = dict(row=row, artifact=json.loads(gzip.decompress(data)),
                             reference=dict(archive=path.relative_to(ROOT).as_posix(), entry=artifact,
                                            sha256=digest(data), phase=phase))
    assert len(rows) == 28
    return rows, infra, dict(path=path.relative_to(ROOT).as_posix(), sha256=prov['sha256'])

def quantile(values, q):
    """Linear interpolation at (n-1)*q; explicitly different from frozen nearest-rank summary P95."""
    xs = sorted(values); i = (len(xs)-1)*q; lo = math.floor(i); hi = math.ceil(i)
    return xs[lo]+(xs[hi]-xs[lo])*(i-lo)

def delta_stats(values):
    return dict(improved=sum(v < 0 for v in values), unchanged=sum(v == 0 for v in values),
                regressed=sum(v > 0 for v in values), sum=sum(values), median=statistics.median(values),
                p25=quantile(values,.25), p75=quantile(values,.75), p95=quantile(values,.95))

def scalar(v):
    if isinstance(v, (float, int)) and not isinstance(v, bool): return str(float(v))
    return encoded(v)

def facts(content):
    """Conservative canonical scalar atoms: entity type/id/version/field/value. Never erase versions within a run."""
    atoms = set()
    def add(typ, eid, version, fields):
        if not all(isinstance(x,str) and x for x in (typ,eid,version)): return
        for key, value in fields.items():
            if key not in ('id','supplierId','itemId','departmentId') and value is not None and not isinstance(value,(dict,list)):
                atoms.add((typ,eid,version,key,scalar(value)))
    def walk(v):
        if isinstance(v,list):
            for x in v: walk(x)
        if not isinstance(v,dict): return
        table=v.get('recordsTable')
        if isinstance(table,dict) and table.get('format')=='all rows; dotted columns expand nested objects; common applies to every row':
            assert table['count']==len(table['rows'])
            for row in table['rows']:
                assert len(row)==len(table['columns'])
                flat=dict(table['common']);flat.update(zip(table['columns'],row));expanded={}
                for dotted,value in flat.items():
                    path=dotted.split('.');node=expanded
                    for part in path[:-1]:node=node.setdefault(part,{})
                    node[path[-1]]=value
                walk(expanded)
        source = v.get('source')
        if isinstance(source,dict) and source.get('backend') == 'ERPNext':
            add(source.get('entityType'),source.get('entityId'),source.get('version'),
                {k:x for k,x in v.items() if k!='source'})
        # Explicit Offer->canonical contract mappings verified against the archived direct records.
        spec=v.get('specifications',{})
        if isinstance(spec,dict) and v.get('sourceRecordId','').startswith('ERPNext:'):
            _,typ,eid,version=v['sourceRecordId'].split(':',3)
            add(typ,eid,version,dict(id=eid,itemId=v.get('productId'),supplierId=v.get('supplierId'),
                referencePrice=v.get('unitPrice'),currency=v.get('currency'),leadTimeDays=v.get('leadTimeDays'),capacityUnits=spec.get('capacityUnits')))
            si=spec.get('itemSource',{})
            add('Item',v.get('productId'),si.get('version'),dict(id=v.get('productId'),name=v.get('productName'),
                gpuMemoryGb=spec.get('gpuMemoryGb'),warranty=v.get('warranty'),active=spec.get('itemActive')))
            add('Supplier',v.get('supplierId'),spec.get('supplierVersion'),dict(id=v.get('supplierId')))
            add('Budget',spec.get('budgetId'),spec.get('budgetVersion'),dict(id=spec.get('budgetId'),
                departmentId=spec.get('departmentId'),available=spec.get('budgetAvailable'),currency=v.get('currency')))
            perf=spec.get('performanceEntity','').removeprefix('Procurement Supplier Performance:')
            add('Procurement Supplier Performance',perf,spec.get('performanceVersion'),
                dict(supplierId=v.get('supplierId'),transactionCount=spec.get('transactionCount'),onTimeRate=spec.get('onTimeRate'),qualityScore=spec.get('qualityScore')))
        for k,x in v.items():
            if k not in ('source','specifications'): walk(x)
    try: walk(json.loads(content))
    except (ValueError,TypeError): pass
    return atoms

def entity_keys(atoms):
    return sorted({a[:3] for a in atoms})

def business_status(request):
    for m in request['messages']:
        if m['type']=='CANONICAL_CONTEXT' and m.get('metadata',{}).get('contextKind')=='procurement_case_state':
            meta=m['metadata']
            return dict(status=meta.get('status'),caseVersion=meta.get('caseVersion'))
    return dict(status='UNCREATED',caseVersion=None)

def analyze_trial(item, phase):
    raw=item['artifact']['raw']; row=item['row']
    contexts=[e for e in raw['events'] if e['type'] in ('CONTEXT_PREPARED','CONTEXT_COMPACTED')]
    assert len(contexts)==len(raw['models'])==row['model_calls']==row['turns']
    events={e['payload']['modelToolCallId']:e for e in raw['events'] if e['type']=='TOOL_REQUESTED'}
    assert len(events)==sum(len(m['turn']['toolCalls']) for m in raw['models'])
    executions={e['toolCallId']:e for e in raw['executions']}
    turns=[]; calls=[]
    for index,(model,context) in enumerate(zip(raw['models'],contexts),1):
        payload=context['payload'];request=model['request'];usage=model['turn']['usage']; comp=composition(request)
        stage=payload.get('selection_stage','NOT_RECORDED_PHASE4')
        record=dict(turn=index,stage=stage,business_state=business_status(request),
            timestamp=context['createdAt'],visible_tools=len(request['tools']),visible_names=[t['name'] for t in request['tools']],
            composition=comp,provider_input=usage['promptTokens'],provider_output=usage['completionTokens'],
            tool_calls=len(model['turn']['toolCalls']),assistant_text_estimate=estimate(model['turn'].get('assistantText','')),
            reasoning_content_present=bool(model['turn'].get('reasoningContent')),reasoning_text_estimate=estimate(model['turn'].get('reasoningContent','')),
            hidden_tools=payload.get('hidden_tools',{}),candidate_schema=payload.get('candidate_schema_estimated_tokens'),
            schema_saved=payload.get('candidate_schema_estimated_tokens',comp['tool_schema'])-comp['tool_schema'])
        if phase=='phase5':
            assert payload['selected_tool_names']==record['visible_names']
            assert payload['tool_schema_estimated_tokens']==comp['tool_schema']
        turns.append(record)
        visible_facts=set()
        for m in request['messages']:
            if m['type']=='TOOL_RESULT': visible_facts |= facts(m['content'])
        for call in model['turn']['toolCalls']:
            ev=events[call['toolCallId']];eid=ev['payload']['toolCallId'];execution=executions.get(eid)
            result=(execution or {}).get('result') or {}
            actual_facts=facts(result.get('content','')) if result.get('success') else set()
            is_read=call['toolName'].startswith('mcp.erpnext.') or call['toolName']=='procurement_supplier_search'
            # Only results from earlier model turns can be prior model knowledge; same-batch results are excluded.
            prior=[c for c in calls if c['turn']<index and c['success'] and c['is_read']]
            previous_facts=set().union(*(c['_atoms'] for c in prior)) if prior else set()
            overlap=actual_facts & previous_facts
            exact=[c for c in prior if c['tool']==call['toolName'] and c['arguments']==call['arguments'] and c['_atoms']==actual_facts and actual_facts]
            different=[c for c in prior if c['tool']!=call['toolName'] and c['_atoms'] & actual_facts]
            cross_stage=[c for c in prior if phase=='phase5' and c['stage']!=stage and c['_atoms'] & actual_facts]
            failed_prior=[c for c in calls if c['turn']<index and c['tool']==call['toolName'] and c['arguments']==call['arguments'] and not c['success'] and 'injected' in c['error']]
            fault='injected' in result.get('errorMessage','')
            if fault or failed_prior: category='D_RECOVERY_OR_INJECTED_FAULT'
            elif exact: category='A_EXACT_REPEAT_SAME_FACT_VERSION'
            elif cross_stage and overlap: category='C_STAGE_TRANSITION_FACT_OVERLAP'
            elif different and overlap: category='B_EQUIVALENT_FACT_OVERLAP'
            elif is_read and actual_facts and not overlap: category='E_ADDITIONAL_FACT_ACQUISITION'
            else: category='UNKNOWN'
            obj=dict(call_index=len(calls)+1,turn=index,stage=stage,tool=call['toolName'],arguments=call['arguments'],
                tool_call_id=eid,timestamp=ev['createdAt'],result_timestamp=(execution or {}).get('updatedAt'),
                execution_index=raw['executions'].index(execution) if execution else None,
                success=bool(result.get('success')),error=result.get('errorMessage',''),is_read=is_read,
                category=category if is_read else 'UNKNOWN',fact_atoms=len(actual_facts),prior_overlap_atoms=len(overlap),
                novel_atoms=len(actual_facts-previous_facts),visible_before_call_overlap_atoms=len(actual_facts&visible_facts),
                entities=entity_keys(actual_facts),exact_repeat_prior=[c['call_index'] for c in exact],
                prior_overlap_entity_types=sorted({a[0] for a in overlap}),novel_entity_types=sorted({a[0] for a in actual_facts-previous_facts}),
                equivalent_fact_prior=[c['call_index'] for c in different],stage_transition_overlap_prior=[c['call_index'] for c in cross_stage],
                overlap_fact_samples=sorted(overlap)[:12],_atoms=actual_facts,
                hidden_alternatives=[],new_fact_acquisition_is_not_proof_of_necessary_reasoning=True)
            calls.append(obj)
    assert len(calls)==row['tool_calls']
    stages=[t['stage'] for t in turns]
    # Case-status transitions are distinct from exposure labels such as RFQ_PREPARATION.
    statuses=[t['business_state']['status'] for t in turns]
    model_input=sum(t['provider_input'] for t in turns);model_output=sum(t['provider_output'] for t in turns)
    guard_input=sum(c.get('usage',{}).get('promptTokens',0) for c in raw['inputChecks'])
    guard_output=sum(c.get('usage',{}).get('completionTokens',0) for c in raw['inputChecks'])
    assert model_input+guard_input==row['input_tokens'] and model_output+guard_output==row['output_tokens']
    for call in calls:
        if not call['is_read'] or not call['_atoms']:continue
        peers=[c for c in calls if c['turn']==call['turn'] and c['call_index']!=call['call_index'] and c['is_read'] and c['tool']!=call['tool'] and c['_atoms'] & call['_atoms']]
        call['same_batch_overlap_calls']=[c['call_index'] for c in peers]
        if call['category']=='E_ADDITIONAL_FACT_ACQUISITION' and peers:call['category']='B_SAME_BATCH_FACT_OVERLAP'
        hidden=turns[call['turn']-1]['hidden_tools']
        # Observed hidden substitutes only: requires another successful tool returning exactly shared atoms in this run.
        alternatives=[c for c in calls if c['tool'] in hidden and c['tool']!=call['tool'] and c['is_read'] and c['_atoms']&call['_atoms']]
        call['hidden_alternatives']=[dict(tool=c['tool'],example_call=c['call_index'],shared_atoms=len(c['_atoms']&call['_atoms'])) for c in alternatives]
    metrics={k:row[k] for k in ('turns','tool_calls','tool_errors','input_tokens','output_tokens','total_tokens','peak_context_tokens','task_latency_ms')}
    metrics.update(model_input_tokens=model_input,model_output_tokens=model_output,guardrail_input_tokens=guard_input,
                   guardrail_output_tokens=guard_output,visible_tools_mean=statistics.mean(t['visible_tools'] for t in turns),
                   schema_tokens=sum(t['composition']['tool_schema'] for t in turns),
                   schema_tokens_mean=statistics.mean(t['composition']['tool_schema'] for t in turns),
                   direct_schema_saved=sum(t['schema_saved'] for t in turns),
                   selection_stage_transitions=sum(a!=b for a,b in zip(stages,stages[1:])) if phase=='phase5' else None,
                   business_status_transitions=sum(a!=b for a,b in zip(statuses,statuses[1:])))
    return dict(metrics=metrics,turns=turns,calls=calls,reference=item['reference'])

def mark_surplus(a,b):
    """Match same name+args first, then same-name occurrence. Unmatched is count surplus, not a causal alignment."""
    available=set(range(len(a)))
    for c in b:
        match=next((i for i in sorted(available) if a[i]['tool']==c['tool'] and a[i]['arguments']==c['arguments']),None)
        if match is not None:available.remove(match);c['paired_phase4_call']=a[match]['call_index'];c['pair_method']='same_name_arguments'
    for c in b:
        if 'paired_phase4_call' in c:continue
        match=next((i for i in sorted(available) if a[i]['tool']==c['tool']),None)
        if match is not None:available.remove(match);c['paired_phase4_call']=a[match]['call_index'];c['pair_method']='same_name_occurrence_changed_arguments'
        else:c['paired_phase4_call']=None;c['pair_method']='phase5_tool_name_count_surplus'
    return [c for c in b if c['paired_phase4_call'] is None],[a[i] for i in sorted(available)]

def main():
    p4,infra4,ref4=load('phase4','context-engineering');p5,infra5,ref5=load('phase5','dynamic-tool-loading')
    assert set(p4)==set(p5)
    paired=[];sequences={};turn_pairs={};gate=[]
    for tid in sorted(p4):
        old,new=p4[tid],p5[tid]
        assert old['artifact']['task']==new['artifact']['task']
        for k in ('profile_prompt_sha256','model_options','max_tool_execution_attempts'):
            if old['row']['controls'][k]!=new['row']['controls'][k]:gate.append([tid,k])
        for k in ('configured_model','resolved_models'):
            if old['row'].get(k)!=new['row'].get(k):gate.append([tid,k])
        definitions={t['name']:schema_hash([t]) for m in old['artifact']['raw']['models'] for t in m['request']['tools']}
        for m in new['artifact']['raw']['models']:
            for t in m['request']['tools']:
                if definitions.get(t['name'])!=schema_hash([t]):gate.append([tid,'tool_contract',t['name']])
        a,b=analyze_trial(old,'phase4'),analyze_trial(new,'phase5')
        original_definitions={t['name']:t for m in old['artifact']['raw']['models'] for t in m['request']['tools']}
        for ev in new['artifact']['raw']['events']:
            if ev['type'] in ('CONTEXT_PREPARED','CONTEXT_COMPACTED'):
                v=ev['payload']
                actual_candidate=sum(estimate(original_definitions[n]['name'])+estimate(original_definitions[n]['description'])+estimate(original_definitions[n]['inputSchema']) for n in v['candidate_tools'])
                assert actual_candidate==v['candidate_schema_estimated_tokens']
        added,removed=mark_surplus(a['calls'],b['calls'])
        old_logical={atom[:2]+atom[3:] for c in a['calls'] if c['is_read'] for atom in c['_atoms']}
        for c in b['calls']:
            logical={atom[:2]+atom[3:] for atom in c['_atoms']}
            c['logical_atoms_also_obtained_in_phase4']=len(logical & old_logical)
        assert len(added)-len(removed)==b['metrics']['tool_calls']-a['metrics']['tool_calls']
        keys=('turns','tool_calls','tool_errors','input_tokens','output_tokens','total_tokens','peak_context_tokens','task_latency_ms')
        entry=dict(task_id=tid,group=old['artifact']['task']['group'],purpose=old['artifact']['task']['purpose'],
            phase4=a['metrics'],phase5=b['metrics'],delta={k:b['metrics'][k]-a['metrics'][k] for k in keys},
            phase5_name_count_surplus=len(added),phase4_name_count_surplus=len(removed),
            surplus_categories=dict(collections.Counter(c['category'] for c in added)),
            direct_schema_saved_per_turn=[t['schema_saved'] for t in b['turns']])
        paired.append(entry)
        for trial in (a,b):
            for c in trial['calls']:del c['_atoms']
        sequences[tid]=dict(phase4=a,phase5=b,phase5_surplus_call_indices=[c['call_index'] for c in added],
            phase4_surplus_call_indices=[c['call_index'] for c in removed])
        turn_pairs[tid]=[dict(ordinal=i+1,phase4=a['turns'][i] if i<len(a['turns']) else None,
                            phase5=b['turns'][i] if i<len(b['turns']) else None,
                            composition_delta={k:b['turns'][i]['composition'][k]-a['turns'][i]['composition'][k] for k in a['turns'][i]['composition']} if i<min(len(a['turns']),len(b['turns'])) else None)
                         for i in range(max(len(a['turns']),len(b['turns'])))]
    assert not gate, gate
    result=dict(gate='COMPARABLE_FOR_OFFLINE_DESCRIPTIVE_AUDIT',control_mismatches=gate,sources=[ref4,ref5],
                pairing='same task_id, trial 1; unique valid dev result, infra attempt excluded; same task/controls/contracts',
                immutable_model_build_available=False,infra_excluded=[dict(task_id=r['task_id'],attempt_id=r['attempt_id']) for r in infra4+infra5],tasks=paired)
    write('phase4-vs-phase5-by-task.json',result)
    write('tool-sequences.json',dict(method='Name-count surplus reconciles net counts; same-name changed-argument pairing is not semantic or causal. Categories are deterministic proxies; overlap does not imply unnecessary execution. E proves only new facts, not genuine reasoning necessity. Non-read calls UNKNOWN.',tasks=sequences))
    write('turn-composition.json',dict(alignment='Ordinal turn only; different business state/requests are not causal paired turns. Includes unmatched tails. Turn count excludes input guardrail.',tasks=turn_pairs))
    summary={}
    summary['delta_statistics']={k:delta_stats([p['delta'][k] for p in paired]) for k in keys}
    summary['delta_quantiles']='linear interpolation (n-1)*q; frozen phase-level P95 uses nearest rank ceil(n*q)'
    summary['top_input_increases']=sorted([dict(task_id=p['task_id'],**p['delta']) for p in paired],key=lambda p:p['input_tokens'],reverse=True)
    summary['groups']={}
    for group in sorted({p['group'] for p in paired}):
        subset=[p for p in paired if p['group']==group];item={'tasks':[p['task_id'] for p in subset]}
        for phase in ('phase4','phase5'):
            ts=[t for p in subset for t in sequences[p['task_id']][phase]['turns']]
            item[phase]={k:sum(p[phase][k] for p in subset) for k in ('turns','tool_calls','input_tokens','total_tokens','schema_tokens')}
            item[phase].update(visible_tools_mean=statistics.mean(t['visible_tools'] for t in ts),schema_mean=statistics.mean(t['composition']['tool_schema'] for t in ts),peak_context_mean=statistics.mean(p[phase]['peak_context_tokens'] for p in subset))
        item['input_tasks']=delta_stats([p['delta']['input_tokens'] for p in subset]);summary['groups'][group]=item
    summary['composition']={};summary['turn_normalized']={}
    for phase in ('phase4','phase5'):
        ts=[t for s in sequences.values() for t in s[phase]['turns']];cs=[c for s in sequences.values() for c in s[phase]['calls']]
        sums={k:sum(t['composition'][k] for t in ts) for k in ts[0]['composition']}
        summary['composition'][phase]=sums
        summary['turn_normalized'][phase]=dict(turns=len(ts),provider_model_input_total=sum(t['provider_input'] for t in ts),
            provider_input_including_guardrail=sum(p[phase]['input_tokens'] for p in paired),
            model_input_per_turn=sum(t['provider_input'] for t in ts)/len(ts),
            input_including_guardrail_per_model_turn=sum(p[phase]['input_tokens'] for p in paired)/len(ts),
            schema_per_turn=sums['tool_schema']/len(ts),tool_results_per_turn=sums['tool_result']/len(ts),
            tool_calls_per_turn=len(cs)/len(ts),output_per_turn=sum(t['provider_output'] for t in ts)/len(ts),
            assistant_text_estimate=sum(t['assistant_text_estimate'] for t in ts),reasoning_text_estimate=sum(t['reasoning_text_estimate'] for t in ts),
            reasoning_present_turns=sum(t['reasoning_content_present'] for t in ts))
    summary['exposure_stages']={}
    for stage in sorted({t['stage'] for s in sequences.values() for t in s['phase5']['turns']}):
        ts=[t for s in sequences.values() for t in s['phase5']['turns'] if t['stage']==stage]
        summary['exposure_stages'][stage]=dict(turns=len(ts),visible_tools_mean=statistics.mean(t['visible_tools'] for t in ts),
            candidate_schema_mean=statistics.mean(t['candidate_schema'] for t in ts),selected_schema_mean=statistics.mean(t['composition']['tool_schema'] for t in ts),
            direct_schema_saved=sum(t['schema_saved'] for t in ts),saved_per_turn=statistics.mean(t['schema_saved'] for t in ts))
    summary['schema_direct']=dict(total=sum(p['phase5']['direct_schema_saved'] for p in paired),
        per_task_mean=statistics.mean(p['phase5']['direct_schema_saved'] for p in paired),
        per_model_turn=sum(p['phase5']['direct_schema_saved'] for p in paired)/summary['turn_normalized']['phase5']['turns'])
    growth=[p for p in paired if p['delta']['tool_calls']>0]
    summary['growth_tasks']=[p['task_id'] for p in growth]
    summary['growth_task_surplus_categories']=dict(sum((collections.Counter(p['surplus_categories']) for p in growth),collections.Counter()))
    summary['growth_task_accounting']=dict(phase5_surplus=sum(p['phase5_name_count_surplus'] for p in growth),phase4_surplus=sum(p['phase4_name_count_surplus'] for p in growth),net=sum(p['delta']['tool_calls'] for p in growth))
    summary['growth_surplus_stage_counts']=dict(collections.Counter(c['stage'] for p in growth for c in sequences[p['task_id']]['phase5']['calls'] if c['paired_phase4_call'] is None))
    summary['peak_tail']={phase:sorted([dict(task_id=p['task_id'],peak=p[phase]['peak_context_tokens']) for p in paired],key=lambda p:p['peak'],reverse=True)[:5] for phase in ('phase4','phase5')}
    summary['all_call_categories']={phase:dict(collections.Counter(c['category'] for s in sequences.values() for c in s[phase]['calls'])) for phase in ('phase4','phase5')}
    summary['fact_family_reacquisition']={}
    for typ in ('Budget','Procurement Purchase History','Supplier','Procurement Supplier Performance','Material Request','Item'):
        summary['fact_family_reacquisition'][typ]={}
        for phase in ('phase4','phase5'):
            cs=[c for s in sequences.values() for c in s[phase]['calls'] if c['is_read'] and typ in {e[0] for e in c['entities']}]
            summary['fact_family_reacquisition'][typ][phase]=dict(read_calls=len(cs),same_version_overlap_calls=sum(typ in c['prior_overlap_entity_types'] for c in cs),
                new_value_calls=sum(typ in c['novel_entity_types'] for c in cs))
    summary['transition_groups']={}
    for count in sorted({p['phase5']['selection_stage_transitions'] for p in paired}):
        ps=[p for p in paired if p['phase5']['selection_stage_transitions']==count]
        summary['transition_groups'][count]=dict(tasks=[p['task_id'] for p in ps],call_delta_sum=sum(p['delta']['tool_calls'] for p in ps),
            call_delta_median=statistics.median(p['delta']['tool_calls'] for p in ps),turn_delta_sum=sum(p['delta']['turns'] for p in ps))
    summary['indirect_selection_cost']=dict(proven_cases=[],candidate_calls=[dict(task_id=tid,call_index=c['call_index'],stage=c['stage'],tool=c['tool'],alternatives=c['hidden_alternatives']) for tid,s in sequences.items() for c in s['phase5']['calls'] if c['hidden_alternatives']],
        rule='Candidate overlap with a hidden read tool is not proof: inspect actual current Case preconditions and whether a multi-tool substitute is necessary. No mental-state inference.')
    for candidate in summary['indirect_selection_cost']['candidate_calls']:
        state=sequences[candidate['task_id']]['phase5']['turns'][sequences[candidate['task_id']]['phase5']['calls'][candidate['call_index']-1]['turn']-1]['business_state']
        candidate['business_state']=state
        candidate['assessment']='NOT_EXECUTABLE_ALTERNATIVE_WITHOUT_CASE' if state['status']=='UNCREATED' and all(a['tool']=='procurement_supplier_search' for a in candidate['alternatives']) else 'UNKNOWN_REQUIRES_REVIEW'
    write('summary.json',summary)
    print(json.dumps(dict(gate=result['gate'],delta_statistics=summary['delta_statistics'],direct_schema=summary['schema_direct'],growth=summary['growth_tasks'],categories=summary['growth_task_surplus_categories']),ensure_ascii=True))

if __name__=='__main__':main()
