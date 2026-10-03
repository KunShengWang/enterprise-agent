"""Container-side Eval setup/diagnostic only. Never used by Agent or runtime provider."""
import hashlib
import json
import os
import sys
from pathlib import Path
sys.path.insert(0, '/workspace/tools/erpnext')
import seed
import frappe

TABLES = dict(suppliers='Supplier',items='Item',item_suppliers='Procurement Item Supplier',
              departments='Department',budgets='Budget',purchase_history='Procurement Purchase History',
              supplier_performance='Procurement Supplier Performance',sample_requisitions='Material Request')
IGNORED = {'creation','modified','modified_by','owner','_user_tags','_comments','_assign','_liked_by','__islocal','__unsaved','__onload'}

def canonical(value, child=False):
    if isinstance(value,dict):
        return {k:canonical(v,isinstance(v,list)) for k,v in sorted(value.items()) if k not in IGNORED and not(child and k=='name')}
    if isinstance(value,list): return [canonical(v,True) for v in value]
    return value

def snapshot():
    facts={}; versions={}
    for group,dt in TABLES.items():
        facts[group]=[]; versions[group]={}
        for row in sorted(seed.load(group),key=lambda r:r['id']):
            doc=frappe.get_doc(dt,row['id']).as_dict()
            facts[group].append(canonical(doc)); versions[group][row['id']]=str(doc['modified'])
    rfqs=[frappe.get_doc('Request for Quotation',name).as_dict() for name in
          frappe.get_all('Request for Quotation',filters={'custom_procurement_seed':seed.SEED},pluck='name',order_by='name asc')]
    digest=hashlib.sha256(json.dumps(facts,sort_keys=True,default=str,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    return dict(facts=facts,counts={k:len(v) for k,v in facts.items()},rfqs=rfqs,versions=versions,businessHash=digest)

def setup(task):
    for change in task['world']['setup']:
        dt=change['doctype']; key=change['id']
        if dt not in TABLES.values() or not key.startswith('V2-'): raise ValueError('setup scope violation')
        doc=frappe.get_doc(dt,key)
        for field,value in change['fields'].items():
            if dt=='Material Request' and field=='quantity': doc.get('items')[0].qty=value
            else: doc.set(field,value)
        doc.save(ignore_permissions=True)
    frappe.db.commit()

if __name__=='__main__':
    os.chdir('/home/frappe/frappe-bench/sites')
    frappe.init(site='frontend',sites_path='/home/frappe/frappe-bench/sites'); frappe.connect(); frappe.set_user('Administrator')
    try:
        command,taskfile,dest=sys.argv[1:4]
        if command=='reset':
            seed.reset(); seed.seed()
            baseline=snapshot()
            if baseline['rfqs']: raise RuntimeError('reset left RFQs')
            task=json.loads(Path(taskfile).read_text(encoding='utf-8')); setup(task)
        state=snapshot()
        if command=='reset': state['baseBusinessHash']=baseline['businessHash']
        Path(dest).write_text(json.dumps(state,sort_keys=True,default=str,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(dict(command=command,counts=state['counts'],rfqCount=len(state['rfqs']),businessHash=state['businessHash'])))
    finally: frappe.destroy()
