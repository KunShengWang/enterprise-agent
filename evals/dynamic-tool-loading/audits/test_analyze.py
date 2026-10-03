"""Small offline correctness checks for attribution math and fact normalization."""
import json
import unittest
from analyze import facts, quantile, mark_surplus

class AuditTests(unittest.TestCase):
    def test_projection_table_retains_same_version_facts(self):
        record={'id':'S1','approved':True,'name':'Supplier','source':{'backend':'ERPNext','entityType':'Supplier','entityId':'S1','version':'v1'}}
        table={'recordsTable':{'format':'all rows; dotted columns expand nested objects; common applies to every row','count':1,
            'common':{'source.backend':'ERPNext','source.entityType':'Supplier'},
            'columns':['id','approved','name','source.entityId','source.version'],'rows':[['S1',True,'Supplier','S1','v1']]}}
        self.assertEqual(facts(json.dumps({'record':record})),facts(json.dumps(table)))
        record['source']['version']='v2'
        self.assertFalse(facts(json.dumps({'record':record})) & facts(json.dumps(table)))

    def test_identity_alone_is_not_evidence_of_repeated_business_fact(self):
        record={'id':'S1','source':{'backend':'ERPNext','entityType':'Supplier','entityId':'S1','version':'v1'}}
        self.assertEqual(set(),facts(json.dumps({'record':record})))

    def test_surplus_reconciles_changed_arguments_and_replaced_tool(self):
        a=[dict(call_index=1,tool='list',arguments={}),dict(call_index=2,tool='read',arguments={'id':'1'})]
        b=[dict(call_index=1,tool='read',arguments={'id':'2'}),dict(call_index=2,tool='read',arguments={'id':'1'}),dict(call_index=3,tool='read',arguments={'id':'3'})]
        added,removed=mark_surplus(a,b)
        self.assertEqual(2,len(added));self.assertEqual(1,len(removed));self.assertEqual(2,b[1]['paired_phase4_call'])
        self.assertEqual(len(b)-len(a),len(added)-len(removed))

    def test_quantile_uses_documented_interpolation(self):
        self.assertEqual(7.5,quantile([0,10,20,30],.25))
        self.assertAlmostEqual(28.5,quantile([0,10,20,30],.95))

if __name__=='__main__':unittest.main()
