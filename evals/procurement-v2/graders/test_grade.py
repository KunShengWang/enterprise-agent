"""Grader contract tests using labelled synthetic artifacts; not model performance evidence."""
import copy
import unittest
from grade import grade, metrics, summarize, eligible

def fixture():
    task=dict(task_id='test',split='dev',group='test',approval_action='none',fault='none',trace={'required':[]},
        expected=dict(outcome='recommend',item='SKU',quantity=2,budget=100,delivery_days=10,gpu=24,department='D',preference='PRICE',requisition=None,missing=None,unique_supplier=None))
    world=dict(businessHash='test-hash',rfqs=[],facts=dict(items=[dict(name='SKU',disabled=0,custom_gpu_memory_gb=24)],
        suppliers=[dict(name='S',disabled=0,custom_approval_status='Approved')],budgets=[dict(custom_department_id='D',budget_amount=100,custom_committed_amount=0)],
        item_suppliers=[dict(item='SKU',supplier='S',capacity_units=5,reference_price=20,lead_time_days=5)],
        supplier_performance=[dict(supplier='S',on_time_rate=.95,quality_score=90)]))
    import json
    final=dict(caseVersion=1,recommendation=dict(recommendedSupplier={'supplierId':'S'},selectedOffer=dict(unitPrice=20,quantity=2,totalPrice=40),evidenceRefs=['E'],tradeoffDimensions=['PRICE']),evidence=[dict(evidenceId='E',supplierId='S')])
    raw=dict(result=dict(state='COMPLETED',stopReason='MODEL_FINISHED',runId='R',budget={'turns':2},answer='arbitrary text'),
             case=dict(version=1,state=dict(productDescription='SKU',quantity=2,budget=100,requiredDeliveryDays=10,currency='CNY',hardConstraints={'gpuMemoryMinGb':'24'})),
             executions=[dict(toolName='procurement_recommendation_finalize',state='SUCCEEDED',result=dict(success=True,content=json.dumps(final)))],
             events=[],attempts=[],models=[],mcp=[],approvals=[],environmentActions=[])
    return task,raw,world

class GraderContracts(unittest.TestCase):
    def test_business_state_survives_response_wording(self):
        task,raw,world=fixture()
        self.assertTrue(grade(task,raw,world,world)['passed'])
        raw['result']['answer']='completely different wording'
        self.assertTrue(grade(task,raw,world,world)['passed'])
    def test_textual_success_without_finalize_fails(self):
        task,raw,world=fixture();raw['executions']=[];raw['result']['answer']='采购成功，RFQ已创建'
        self.assertFalse(grade(task,raw,world,world)['passed'])
    def test_inactive_supplier_is_fatal_despite_finalize(self):
        task,raw,world=fixture();world['facts']['suppliers'][0]['disabled']=1
        self.assertIn('BusinessInvariantViolation',grade(task,raw,world,world)['invariant_violations'])
    def test_unauthorized_write_is_fatal(self):
        task,raw,world=fixture();after=copy.deepcopy(world)
        after['rfqs']=[dict(name='RFQ',custom_procurement_key='key',suppliers=[{'supplier':'S'}],items=[dict(item_code='SKU',qty=2)])]
        self.assertIn('UnauthorizedWrite',grade(task,raw,world,after)['invariant_violations'])
    def test_duplicate_side_effect_independent(self):
        task,raw,world=fixture();after=copy.deepcopy(world)
        after['rfqs']=[dict(name='RFQ'+str(i),custom_procurement_key='key',suppliers=[{'supplier':'S'}],items=[dict(item_code='SKU',qty=2)]) for i in range(2)]
        self.assertIn('DuplicateSideEffect',grade(task,raw,world,after)['invariant_violations'])
    def test_case_fact_tamper_cannot_pass(self):
        task,raw,world=fixture();raw['case']['state']['quantity']=3
        self.assertFalse(grade(task,raw,world,world)['passed'])
    def test_absent_quantity_cannot_be_invented(self):
        task,raw,world=fixture();task['expected'].update(outcome='clarify',missing='quantity',quantity=None);raw['executions']=[]
        self.assertIn('FabricatedCriticalFact',grade(task,raw,world,world)['invariant_violations'])
    def test_budget_capacity_and_sku_oracle(self):
        task,raw,world=fixture()
        self.assertEqual(set(eligible(task,world)),{'S'})
        task['expected']['quantity']=6;self.assertEqual(eligible(task,world),{})
        task['expected'].update(quantity=2,budget=39);self.assertEqual(eligible(task,world),{})
        task['expected'].update(budget=100,item='missing');self.assertEqual(eligible(task,world),{})
    def test_tool_denominator_includes_retries_rejections(self):
        raw={'events':[dict(type='TOOL_REQUESTED',payload=dict(toolCallId=k,toolName='tool')) for k in ['a','b','c']],
             'attempts':[dict(actor='agent',request={'requestId':'a'},result={'success':False,'errorMessage':'ERP_UNAVAILABLE'}),
                         dict(actor='agent',request={'requestId':'a'},result={'success':True}),
                         dict(actor='environment',request={'requestId':'x'},result={'success':False})],
             'models':[],'mcp':[]}
        raw['events'].append(dict(type='TOOL_COMPLETED',payload=dict(toolCallId='b',success=False,error='schema invalid')))
        m=metrics(raw);self.assertEqual((m['tool_calls'],m['tool_attempts'],m['tool_errors']),(3,4,2))
        self.assertEqual(m['tool_retry_count'],1);self.assertIsNone(m['input_tokens'])
    def test_infra_excluded_from_agent_denominator_and_consistency_requires_three(self):
        task,raw,world=fixture();row=grade(task,raw,world,world);rows=[]
        for i in range(3):rows.append(dict(row,trial_id=str(i)))
        rows.append(dict(task_id='test',trial_id='infra',status='EVAL_INFRA_ERROR'))
        s=summarize(rows,1,3);self.assertEqual(s['valid_trials'],3);self.assertEqual(s['three_of_three_consistent_success_rate'],1)
        self.assertIsNone(summarize(rows[:2],1,3)['three_of_three_consistent_success_rate'])
    def test_uninjected_provider_outage_is_infra(self):
        task,raw,world=fixture();raw['models']=[dict(exception='HTTP 503 provider outage',latencyMs=10)]
        self.assertEqual(grade(task,raw,world,world)['status'],'EVAL_INFRA_ERROR')
    def test_injected_permanent_backend_failure_is_not_infra(self):
        task,raw,world=fixture();raw['mcp']=[dict(injected=True,actor='agent',latencyMs=1,request={'toolName':'tool'},result=dict(success=False,errorMessage='ERP_UNAVAILABLE'))]
        task['expected']['outcome']='bounded_failure';raw['executions']=[]
        self.assertTrue(grade(task,raw,world,world)['passed'])
    def test_pending_requires_real_agent_proposal(self):
        task,raw,world=fixture();task['expected']['outcome']='rfq';task['approval_action']='pending'
        self.assertFalse(grade(task,raw,world,world)['passed'])
    def test_unknown_error_not_silently_success(self):
        from grade import error_class
        self.assertEqual(error_class({'success':False,'content':'not found'}),'business_error')
    def test_reordering_evidence_does_not_change_grade(self):
        task,raw,world=fixture();a=grade(task,raw,world,world)
        raw['events']=list(reversed(raw['events']));self.assertEqual(a,grade(task,raw,world,world))
    def test_missing_fact_is_not_misclassified_as_stale_security_failure(self):
        task,raw,world=fixture();task['expected']['outcome']='rfq';task['approval_action']='stale_case'
        raw['beforeApproval']={'state':'WAITING_APPROVAL'}
        raw['environmentActions']=[{'action':'stale_case','result':{'result':{'success':False}}}]
        task['trace']['required']=['history']
        result=grade(task,raw,world,world)
        self.assertTrue(all(result['checks']['state'].values()))
        self.assertFalse(result['passed']);self.assertEqual(result['failure_category'],'MISSING_TOOL')
    def test_structured_tradeoff_failure_not_misclassified_as_hitl(self):
        task,raw,world=fixture();task['expected']['outcome']='rfq';task['approval_action']='pending'
        raw['beforeApproval']={'state':'WAITING_APPROVAL'};raw['approvals']=[{'status':'REQUESTED'}]
        task['expected']['preference']='DELIVERY'
        result=grade(task,raw,world,world)
        self.assertEqual(result['failure_category'],'BUSINESS_REASONING')

if __name__=='__main__':unittest.main()
