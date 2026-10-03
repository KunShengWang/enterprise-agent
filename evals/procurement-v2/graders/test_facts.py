"""Fact contracts: alternative sources accepted; stale/fabricated facts rejected."""
import copy
import json
import unittest
from facts import fact_witnesses

def fixture():
    version='2026-10-01T00:00:00Z'
    task={'expected':dict(item='I',department='D',quantity=2,requisition='R')}
    world=dict(facts=dict(items=[dict(name='I')],suppliers=[dict(name='S',supplier_name='Supplier',disabled=0,custom_approval_status='Approved')],
        item_suppliers=[dict(name='O',item='I',supplier='S',reference_price=10,currency='CNY',capacity_units=5,lead_time_days=2)],
        supplier_performance=[dict(name='S',supplier='S',transaction_count=3,on_time_rate=.9,quality_score=95)],
        budgets=[dict(name='B',custom_department_id='D',budget_amount=100,custom_committed_amount=20,custom_currency='CNY')]),
        versions={group:{key:version} for group,key in [('items','I'),('suppliers','S'),('item_suppliers','O'),('supplier_performance','S'),('budgets','B')]})
    offer=dict(supplierId='S',productId='I',unitPrice=10,currency='CNY',leadTimeDays=2,sourceRecordId='ERPNext:Procurement Item Supplier:O:'+version,
        specifications=dict(capacityUnits=5,supplierVersion=version,performanceEntity='Procurement Supplier Performance:S',performanceVersion=version,
            transactionCount=3,onTimeRate=.9,qualityScore=95,budgetId='B',departmentId='D',budgetAvailable=80,budgetVersion=version))
    return task,world,offer

def raw(payload, tool='any_valid_canonical_tool'):
    return {'executions':[dict(toolName=tool,state='SUCCEEDED',result=dict(success=True,content=json.dumps(payload)))]}

class FactContracts(unittest.TestCase):
    def test_offer_proves_all_sourcing_facts_without_direct_getters(self):
        t,w,o=fixture();found=fact_witnesses(t,raw({'offers':[o]}),w,'S')
        self.assertTrue(all(found[k] for k in ('budget','supplier','item_suppliers','history','performance')))
    def test_renamed_tool_same_facts_same_verdict(self):
        t,w,o=fixture()
        self.assertEqual(fact_witnesses(t,raw(o,'tool_a'),w,'S'),fact_witnesses(t,raw(o,'tool_b'),w,'S'))
    def test_model_answer_and_arguments_cannot_supply_facts(self):
        t,w,o=fixture();r={'models':[{'turn':{'answer':json.dumps(o)}}],'request':o}
        self.assertFalse(fact_witnesses(t,r,w,'S')['history'])
    def test_hidden_mcp_read_alone_is_not_agent_obtained(self):
        t,w,o=fixture();r={'mcp':[{'actor':'agent','result':{'success':True,'content':json.dumps(o)}}]}
        self.assertFalse(fact_witnesses(t,r,w,'S')['history'])
    def test_stale_performance_version_fails(self):
        t,w,o=fixture();o['specifications']['performanceVersion']='2025-01-01T00:00:00Z'
        self.assertFalse(fact_witnesses(t,raw(o),w,'S')['history'])
    def test_changed_performance_value_fails(self):
        t,w,o=fixture();o['specifications']['onTimeRate']=1
        self.assertFalse(fact_witnesses(t,raw(o),w,'S')['history'])
    def test_unrelated_supplier_cannot_prove_selected_supplier_history(self):
        t,w,o=fixture();self.assertFalse(fact_witnesses(t,raw(o),w,'OTHER')['history'])
    def test_unrelated_item_and_forged_relation_rejected(self):
        t,w,o=fixture();o['productId']='OTHER'
        self.assertFalse(fact_witnesses(t,raw(o),w,'S')['item_suppliers'])
        t,w,o=fixture();o['unitPrice']=9
        self.assertFalse(fact_witnesses(t,raw(o),w,'S')['item_suppliers'])
    def test_stale_or_wrong_budget_not_accepted(self):
        t,w,o=fixture();o['specifications']['budgetAvailable']=90
        self.assertFalse(fact_witnesses(t,raw(o),w,'S')['budget'])
    def test_failed_tool_result_not_accepted(self):
        t,w,o=fixture();r=raw(o);r['executions'][0]['result']['success']=False
        self.assertFalse(fact_witnesses(t,r,w,'S')['history'])
    def test_direct_performance_equivalent_source(self):
        t,w,o=fixture();p=dict(supplierId='S',transactionCount=3,onTimeRate=.9,qualityScore=95,
            source=dict(backend='ERPNext',entityType='Procurement Supplier Performance',entityId='S',version='2026-10-01T00:00:00Z'))
        self.assertTrue(fact_witnesses(t,raw({'record':p}),w,'S')['history'])
        p['source']['backend']='untrusted'
        self.assertFalse(fact_witnesses(t,raw({'record':p}),w,'S')['history'])
    def test_canonical_transaction_row_is_an_alternative_history_source(self):
        t,w,o=fixture();version='2026-10-01T00:00:00Z'
        w['facts']['purchase_history']=[dict(name='H',supplier='S',item='I',quantity=2,unit_price=11,currency='CNY',ordered_on='2026-01-01',delivered_on='2026-01-03')]
        w['versions']['purchase_history']={'H':version}
        h=dict(id='H',supplierId='S',itemId='I',quantity=2,unitPrice=11,currency='CNY',orderedOn='2026-01-01',deliveredOn='2026-01-03',source=dict(backend='ERPNext',entityType='Procurement Purchase History',entityId='H',version=version))
        self.assertTrue(fact_witnesses(t,raw({'records':[h]}),w,'S')['history'])
        h['unitPrice']=10
        self.assertFalse(fact_witnesses(t,raw({'records':[h]}),w,'S')['history'])
    def test_not_found_requires_matching_absent_entity_and_real_error(self):
        t,w,o=fixture();t['expected']['item']='MISSING'
        r={'mcp':[dict(actor='agent',injected=False,request={'arguments':{'id':'MISSING'}},result={'success':False,'content':'{"error":{"code":"ERP_NOT_FOUND"}}'})]}
        self.assertTrue(fact_witnesses(t,r,w)['item_attempt'])
        r['mcp'][0]['injected']=True;self.assertFalse(fact_witnesses(t,r,w)['item_attempt'])

if __name__=='__main__':unittest.main()
