"""Run with bench's Python inside the ERPNext container. Uses Frappe ORM, not ERP source patches.
Only a dedicated local frontend site is supported. Runtime never reads these files.
"""
import json
import os
import secrets
import sys
from pathlib import Path
import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields

SITE='frontend'
COMPANY='Synthetic AI Labs'
SEED='v2-workstations'
DATA=Path('/workspace/seeds')
OUTPUT=Path('/workspace/output')
KEY=''
def load(name):
    return json.loads((DATA/(name+'.json')).read_text(encoding='utf-8'))

def upsert(doctype, name, values):
    global KEY
    KEY=f'{doctype}/{name}'
    if frappe.db.exists(doctype,name):
        doc=frappe.get_doc(doctype,name)
        changed=any(doc.get(k)!=v for k,v in values.items())
        if changed:
            doc.update(values)
            doc.save(ignore_permissions=True)
        return doc
    doc=frappe.get_doc(dict(doctype=doctype,**values))
    doc.insert(ignore_permissions=True, set_name=name)
    return doc

def field(name,kind='Data',options=None,**kw):
    result=dict(fieldname=name,label=name.replace('_',' ').title(),fieldtype=kind,**kw)
    if options: result['options']=options
    return result

def schema():
    if not frappe.db.exists('Role','Procurement Demo'):
        frappe.get_doc(dict(doctype='Role',role_name='Procurement Demo',desk_access=0)).insert(ignore_permissions=True)
    common=[field('custom_procurement_seed')]
    create_custom_fields({
        'Supplier':common+[field('custom_approval_status','Select','Approved\nInactive'),field('custom_capacity')],
        'Item':common+[field('custom_gpu_memory_gb','Int'),field('custom_warranty')],
        'Material Request':common+[field('custom_department_id')],
        'Budget':common+[field('custom_department_id'),field('custom_committed_amount','Currency'),field('custom_currency')],
        'Request for Quotation':common+[field('custom_procurement_key',unique=1),field('custom_request_digest')]
    },update=True)
    definitions={
        'Procurement Item Supplier':[field('item','Link','Item',reqd=1),field('supplier','Link','Supplier',reqd=1),
            field('reference_price','Currency',reqd=1),field('currency','Link','Currency',reqd=1),field('lead_time_days','Int'),field('capacity_units','Int')],
        'Procurement Purchase History':[field('supplier','Link','Supplier',reqd=1),field('item','Link','Item',reqd=1),
            field('quantity','Int'),field('unit_price','Currency'),field('currency','Link','Currency'),field('ordered_on','Date'),field('delivered_on','Date')],
        'Procurement Supplier Performance':[field('supplier','Link','Supplier',reqd=1),field('transaction_count','Int'),
            field('on_time_rate','Float'),field('quality_score','Float'),field('observation_date','Date')]
    }
    for name,fields in definitions.items():
        if not frappe.db.exists('DocType',name):
            frappe.get_doc(dict(doctype='DocType',name=name,module='Buying',custom=1,autoname='prompt',
                fields=fields,permissions=[dict(role='System Manager',read=1,write=1,create=1,delete=1),
                                          dict(role='Procurement Demo',read=1)])).insert(ignore_permissions=True)
    # Runtime identity can read procurement facts and create draft RFQs, but not administer the site.
    from frappe.permissions import add_permission, update_permission_property
    for dt in ['Supplier','Item','Budget','Material Request','Request for Quotation']:
        add_permission(dt,'Procurement Demo',0)
        for right in ['read','select']:
            update_permission_property(dt,'Procurement Demo',0,right,1)
        if dt=='Request for Quotation':
            update_permission_property(dt,'Procurement Demo',0,'create',1)

def seed():
    schema()
    # A fresh official site has not gone through Desk's setup wizard yet.
    if not frappe.db.exists('Warehouse Type','Transit'):
        from erpnext.setup.setup_wizard.operations.install_fixtures import install
        install('China')
    frappe.db.set_single_value('System Settings','time_zone','UTC')
    frappe.db.set_single_value('Buying Settings','supp_master_name','Supplier Name')
    upsert('Company',COMPANY,dict(company_name=COMPANY,abbr='SAL',default_currency='CNY',country='China',
                                chart_of_accounts='Standard',create_chart_of_accounts_based_on='Standard Template'))
    upsert('Fiscal Year','2026',dict(year='2026',year_start_date='2026-01-01',year_end_date='2026-12-31'))
    upsert('Item Group','AI Workstation',dict(item_group_name='AI Workstation',parent_item_group='All Item Groups',is_group=0))
    account=frappe.db.get_value('Account',dict(company=COMPANY,root_type='Expense',is_group=0),'name')
    if not account: raise ValueError('Company has no expense account')
    root_cc=frappe.db.get_value('Cost Center',dict(company=COMPANY,is_group=1),'name')
    cost_centers={}
    for row in load('departments'):
        upsert('Department',row['id'],dict(department_name=row['id'],company=COMPANY))
        cc=upsert('Cost Center',row['id']+' - SAL',dict(cost_center_name=row['id'],company=COMPANY,parent_cost_center=root_cc,is_group=0))
        cost_centers[row['id']]=cc.name
    for row in load('suppliers'):
        upsert('Supplier',row['id'],dict(supplier_name=row['name'],supplier_type='Company',supplier_group='All Supplier Groups',
            disabled=int(not row['approved']),custom_approval_status='Approved' if row['approved'] else 'Inactive',
            custom_capacity=row['capacity'],custom_procurement_seed=SEED))
    for row in load('items'):
        upsert('Item',row['id'],dict(item_code=row['id'],item_name=row['name'],item_group='AI Workstation',stock_uom=row['uom'],
            is_stock_item=0,custom_gpu_memory_gb=row['gpu_memory_gb'],custom_warranty=row['warranty'],custom_procurement_seed=SEED))
    for filename,doctype in [('item_suppliers','Procurement Item Supplier'),('purchase_history','Procurement Purchase History'),
                             ('supplier_performance','Procurement Supplier Performance')]:
        for row in load(filename):
            upsert(doctype,row['id'],{k:v for k,v in row.items() if k!='id'})
    for row in load('budgets'):
        upsert('Budget',row['id'],dict(budget_against='Cost Center',company=COMPANY,cost_center=cost_centers[row['department']],
            account=account,budget_amount=row['allocated'],from_fiscal_year=row['fiscal_year'],to_fiscal_year=row['fiscal_year'],
            distribution_frequency='Yearly',custom_department_id=row['department'],custom_currency=row['currency'],
            custom_committed_amount=row['committed'],custom_procurement_seed=SEED))
    for row in load('sample_requisitions'):
        # Existing stable seed requisitions are not overwritten after user edits.
        if not frappe.db.exists('Material Request',row['id']):
            upsert('Material Request',row['id'],dict(material_request_type='Purchase',company=COMPANY,
                transaction_date='2026-10-01',custom_department_id=row['department'],custom_procurement_seed=SEED,
                items=[dict(item_code=row['item'],qty=row['quantity'],schedule_date=row['required_by'],uom='Nos',stock_uom='Nos',conversion_factor=1)]))
    user=upsert('User','procurement-demo@example.invalid',dict(email='procurement-demo@example.invalid',first_name='Procurement Demo',
                enabled=1,user_type='System User',send_welcome_email=0))
    user.add_roles('Procurement Demo')
    # Local credentials persist across reseeds, never written to versioned files or stdout.
    key=user.api_key or secrets.token_hex(12)
    secret=user.get_password('api_secret',raise_exception=False) or secrets.token_hex(24)
    user.api_key=key; user.api_secret=secret; user.save(ignore_permissions=True)
    env_path=OUTPUT/'runtime.env'
    old={}
    if env_path.exists(): old=dict(line.split('=',1) for line in env_path.read_text().splitlines() if '=' in line)
    signature=old.get('ERPNEXT_RFQ_SIGNING_SECRET',secrets.token_hex(32))
    env_path.write_text(f'ERPNEXT_URL=http://localhost:8088\nERPNEXT_COMPANY={COMPANY}\nERPNEXT_API_KEY={key}\nERPNEXT_API_SECRET={secret}\nERPNEXT_RFQ_SIGNING_SECRET={signature}\n',encoding='utf-8')
    counts={name:len(load(name)) for name in ['suppliers','items','item_suppliers','departments','budgets','purchase_history','supplier_performance','sample_requisitions']}
    # Verify all stable keys actually exist in ERP before reporting import counts.
    mapping=dict(suppliers='Supplier',items='Item',item_suppliers='Procurement Item Supplier',departments='Department',budgets='Budget',
                 purchase_history='Procurement Purchase History',supplier_performance='Procurement Supplier Performance',sample_requisitions='Material Request')
    for name,dt in mapping.items():
        for row in load(name):
            if not frappe.db.exists(dt,row['id']): raise ValueError(f'Missing imported record {dt}/{row["id"]}')
    frappe.db.commit()
    (OUTPUT/'seed-result.json').write_text(json.dumps(counts,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(counts))

def reset():
    schema()
    # Only our dedicated site's exact seeded IDs and marked RFQs; never drop site/volumes or unrelated master data.
    for name in frappe.get_all('Request for Quotation',filters={'custom_procurement_seed':SEED},pluck='name'):
        frappe.delete_doc('Request for Quotation',name,ignore_permissions=True)
    for filename,dt in [('sample_requisitions','Material Request'),('budgets','Budget'),('supplier_performance','Procurement Supplier Performance'),
                        ('purchase_history','Procurement Purchase History'),('item_suppliers','Procurement Item Supplier'),('items','Item'),('suppliers','Supplier')]:
        for row in load(filename):
            if frappe.db.exists(dt,row['id']): frappe.delete_doc(dt,row['id'],ignore_permissions=True)
    frappe.db.commit()

if __name__=='__main__':
    os.chdir('/home/frappe/frappe-bench/sites')
    frappe.init(site=SITE,sites_path='/home/frappe/frappe-bench/sites')
    frappe.connect(); frappe.set_user('Administrator')
    try:
        if len(sys.argv)>1 and sys.argv[1]=='reset': reset()
        seed()
    except Exception as exc:
        frappe.db.rollback()
        print(f'SEED_FAILED record={KEY} errorType={type(exc).__name__}',file=sys.stderr)
        raise
    finally: frappe.destroy()
