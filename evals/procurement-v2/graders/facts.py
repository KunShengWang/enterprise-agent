"""V2.1 fact witnesses from successful canonical results, never tool-name requirements.

Snapshot values AND entity versions are checked. Model prose/arguments and hidden
provider reads do not by themselves prove that the Agent obtained a fact.
"""
import json
from datetime import datetime, timezone

GROUPS = {'Supplier':'suppliers','Item':'items','Budget':'budgets',
          'Procurement Item Supplier':'item_suppliers',
          'Procurement Purchase History':'purchase_history',
          'Procurement Supplier Performance':'supplier_performance',
          'Material Request':'sample_requisitions'}

def instant(value):
    try:
        return datetime.fromisoformat(str(value).replace(' ', 'T').replace('Z', '+00:00')).replace(tzinfo=timezone.utc)
    except (ValueError, TypeError):
        return None

def objects(value, path='$'):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from objects(child, path+'.'+key)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from objects(child, path+f'[{i}]')

def fact_witnesses(task, raw, world, selected_supplier=None):
    expected=task['expected'];facts=world['facts'];versions=world.get('versions',{})
    index={kind:{r.get('name'):r for r in facts.get(group,[])} for kind,group in GROUPS.items()}
    found={k:[] for k in ('budget','item_suppliers','supplier','history','performance','requisition','requisitions','requisition_attempt','item_attempt')}
    def entity(kind, key, version):
        row=index.get(kind,{}).get(key)
        known=versions.get(GROUPS.get(kind),{}).get(key)
        return row if row and known and instant(known)==instant(version) else None
    def relevant(supplier):
        return supplier==selected_supplier if selected_supplier else any(r.get('item')==expected['item'] and r.get('supplier')==supplier for r in facts.get('item_suppliers',[]))
    def add(fact, path, kind, key, version, fields):
        witness=dict(path=path,entity_type=kind,entity_id=key,version=version,verified_fields=fields)
        if witness not in found[fact]:found[fact].append(witness)
    for i,execution in enumerate(raw.get('executions',[])):
        result=execution.get('result') or {}
        if execution.get('state')!='SUCCEEDED' or not result.get('success'):continue
        try:payload=json.loads(result.get('content',''))
        except (ValueError,TypeError):continue
        for path,record in objects(payload,f'executions[{i}].result.content'):
            source=record.get('source')
            if isinstance(source,dict) and source.get('backend')=='ERPNext':
                kind=source.get('entityType');key=source.get('entityId');version=source.get('version')
                row=entity(kind,key,version)
                if not row:continue
                def same(mapping):return all(k in record and record[k]==row.get(v) for k,v in mapping.items())
                def emit(fact,fields):add(fact,path,kind,key,version,fields)
                if kind=='Budget' and row.get('custom_department_id')==expected['department'] and same({'departmentId':'custom_department_id','allocated':'budget_amount','committed':'custom_committed_amount','currency':'custom_currency'}) and record.get('available')==row['budget_amount']-row['custom_committed_amount']:
                    emit('budget',['departmentId','allocated','committed','available','currency'])
                elif kind=='Item' and key==expected['item'] and same({'id':'name','gpuMemoryGb':'custom_gpu_memory_gb'}):emit('item_attempt',['id','gpuMemoryGb'])
                elif kind=='Supplier' and relevant(key) and same({'id':'name','name':'supplier_name'}) and record.get('approved')==(not row.get('disabled') and row.get('custom_approval_status')=='Approved'):
                    emit('supplier',['id','name','approved'])
                elif kind=='Procurement Item Supplier' and row.get('item')==expected['item'] and relevant(row.get('supplier')) and same({'itemId':'item','supplierId':'supplier','referencePrice':'reference_price','capacityUnits':'capacity_units','leadTimeDays':'lead_time_days','currency':'currency'}):
                    emit('item_suppliers',['itemId','supplierId','referencePrice','capacityUnits','leadTimeDays','currency'])
                elif kind=='Procurement Supplier Performance' and relevant(row.get('supplier')) and same({'supplierId':'supplier','transactionCount':'transaction_count','onTimeRate':'on_time_rate','qualityScore':'quality_score'}):
                    emit('history',['transactionCount','onTimeRate','qualityScore']);emit('performance',['transactionCount','onTimeRate','qualityScore'])
                elif kind=='Procurement Purchase History' and row.get('item')==expected['item'] and relevant(row.get('supplier')) and same({'supplierId':'supplier','itemId':'item','quantity':'quantity','unitPrice':'unit_price','orderedOn':'ordered_on','deliveredOn':'delivered_on','currency':'currency'}):
                    emit('history',['quantity','unitPrice','orderedOn','deliveredOn','currency'])
                elif kind=='Material Request':
                    line=(row.get('items') or [{}])[0]
                    if record.get('id')==key and record.get('departmentId')==row.get('custom_department_id') and record.get('itemId')==line.get('item_code') and record.get('quantity')==line.get('qty'):
                        if record['departmentId']==expected['department'] and record['itemId']==expected['item'] and record['quantity']==expected['quantity']:emit('requisitions',['id','departmentId','itemId','quantity'])
                        if key==expected.get('requisition'):
                            emit('requisition',['id','departmentId','itemId','quantity']);emit('requisition_attempt',['id'])
            # Canonical Offer, including Search/Finalize results, carries multiple
            # independently versioned ERP facts. No need to call each direct getter.
            ref=record.get('sourceRecordId','');spec=record.get('specifications')
            if not isinstance(ref,str) or not ref.startswith('ERPNext:Procurement Item Supplier:') or not isinstance(spec,dict):continue
            _,kind,key,version=ref.split(':',3);relation=entity(kind,key,version)
            sid=record.get('supplierId')
            if not relation or not relevant(sid) or record.get('productId')!=expected['item'] or relation.get('item')!=expected['item'] or sid!=relation.get('supplier'):continue
            if not all(record.get(k)==relation.get(v) for k,v in [('unitPrice','reference_price'),('leadTimeDays','lead_time_days'),('currency','currency')]) or spec.get('capacityUnits')!=relation.get('capacity_units'):continue
            add('item_suppliers',path,kind,key,version,['unitPrice','leadTimeDays','currency','capacityUnits'])
            supplier=entity('Supplier',sid,spec.get('supplierVersion'))
            if supplier and not supplier.get('disabled') and supplier.get('custom_approval_status')=='Approved':add('supplier',path,'Supplier',sid,spec['supplierVersion'],['approved via authoritative active Offer contract'])
            performance=entity('Procurement Supplier Performance',sid,spec.get('performanceVersion'))
            if performance and spec.get('performanceEntity')=='Procurement Supplier Performance:'+sid and all(k in spec and spec[k]==performance.get(v) for k,v in [('transactionCount','transaction_count'),('onTimeRate','on_time_rate'),('qualityScore','quality_score')]):
                for fact in ('history','performance'):add(fact,path,'Procurement Supplier Performance',sid,spec['performanceVersion'],['transactionCount','onTimeRate','qualityScore'])
            budget=entity('Budget',spec.get('budgetId'),spec.get('budgetVersion'))
            if budget and budget.get('custom_department_id')==expected['department'] and spec.get('departmentId')==expected['department'] and spec.get('budgetAvailable')==budget['budget_amount']-budget['custom_committed_amount'] and record.get('currency')==budget.get('custom_currency'):
                add('budget',path,'Budget',spec['budgetId'],spec['budgetVersion'],['departmentId','budgetAvailable','currency'])
    # Unknown entity tasks require an authoritative NOT_FOUND observation for that
    # identifier, not a particular getter name or any arbitrary failed tool call.
    for i,entry in enumerate(raw.get('mcp',[])):
        if entry.get('actor')!='agent' or entry.get('injected'):continue
        try:body=json.loads(entry.get('result',{}).get('content',''))
        except (ValueError,TypeError):continue
        if body.get('error',{}).get('code')!='ERP_NOT_FOUND':continue
        key=entry.get('request',{}).get('arguments',{}).get('id')
        for fact,kind,wanted in [('item_attempt','Item',expected['item']),('requisition_attempt','Material Request',expected.get('requisition'))]:
            if key and key==wanted and key not in index[kind]:add(fact,f'mcp[{i}].result','ERP_NOT_FOUND',key,None,['authoritative absence'])
    # Duplicate detection needs both distinct matching requisitions, not a list call.
    if len({x['entity_id'] for x in found['requisitions']})<2:found['requisitions']=[]
    return found
