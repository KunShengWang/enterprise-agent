import copy
import unittest
from acceptance import acceptance, requisition_link_consistency
import json

class WorkflowAcceptanceTests(unittest.TestCase):
    def fixture(self):
        import json
        packet=dict(userInput='x',canonicalCase={},validatedPriorStage={},departmentId='d',stage='REQUISITION')
        return dict(controls=dict(stageProfiles=[dict(stage='REQUISITION',outputSchema=json.dumps(dict(type='object',additionalProperties=False,properties={})),toolScope=[])]),
            stageRuns=[dict(runId='r',executionProfile=dict(name='PROCUREMENT'),request=dict(question=json.dumps(packet),metadata=dict(procurementStage='REQUISITION',agentId='PROCUREMENT',workflowId='w',workflowCaseVersion=-1,workflowCaseId='',validatedStageOutput={})))],
            models=[dict(schemaSha256='fixed',request=dict(runId='r',tools=[],messages=[dict(runId='r',type='USER')]))],result=dict(state='COMPLETED'))
    def test_valid(self):self.assertTrue(acceptance(self.fixture())['passed'])
    def test_illegal_skip(self):
        r=self.fixture();r['stageRuns'][0]['request']['metadata']['procurementStage']='RFQ';self.assertFalse(acceptance(r)['passed'])
    def test_wrong_identity(self):
        r=self.fixture();r['stageRuns'][0]['executionProfile']['name']='SUPERVISOR';self.assertFalse(acceptance(r)['passed'])
    def test_leaked_history(self):
        r=self.fixture();r['models'][0]['request']['messages'][0]['runId']='prior';self.assertFalse(acceptance(r)['passed'])
    def test_changing_tools(self):
        r=self.fixture();m=copy.deepcopy(r['models'][0]);m['schemaSha256']='changed';r['models'].append(m);self.assertFalse(acceptance(r)['passed'])
    def test_forged_version(self):
        r=self.fixture();r['stageRuns'][0]['request']['metadata']['workflowCaseVersion']=4;self.assertFalse(acceptance(r)['passed'])
    def test_invalid_schema(self):
        r=self.fixture();r['stageRuns'][0]['request']['metadata']['validatedStageOutput']={'unknown':1};self.assertFalse(acceptance(r)['passed'])
    def test_failed_run(self):
        r=self.fixture();r['result']['state']='FAILED';self.assertFalse(acceptance(r)['passed'])
    def test_missing_output(self):
        r=self.fixture();del r['stageRuns'][0]['request']['metadata']['validatedStageOutput'];self.assertFalse(acceptance(r)['passed'])

class RequisitionLinkTests(unittest.TestCase):
    def fixture(self):
        identity=dict(caseId='c',caseVersion=1,requisitionBacked=True,canonicalRequisitionId='MR-A',itemId='SKU',quantity=2)
        case=dict(caseId='c',version=2,state=dict(productDescription='SKU',quantity=2))
        snapshot=dict(caseId='c',caseVersion=2,workflowRequisitionRunId='req',canonicalRequisitionId='MR-A',requisitionBacked=True,recommendation=dict(recommendedSupplier=dict(supplierId='SUP')))
        args=dict(caseId='c',caseVersion=2,requisitionId='MR-A',sourceRecommendationToolCallId='final',supplierId='SUP',idempotencyKey='key',productDescription='SKU',quantity=2,currency='CNY',requiredDeliveryDays=10)
        request=dict(toolName='procurement_create_rfq',requestId='write',arguments=args)
        dto=dict(requisitionId='MR-A',supplierId='SUP',idempotencyKey='key',itemId='SKU',quantity=2,currency='CNY',deliveryDays=10)
        raw=dict(stageRuns=[dict(runId='req',request=dict(metadata=dict(procurementStage='REQUISITION',workflowCanonicalRequisition=identity))),
            dict(runId='src',request=dict(metadata=dict(procurementStage='SOURCING',workflowCaseVersion=1))),
            dict(runId='rfq',request=dict(metadata=dict(procurementStage='RFQ',workflowFinalizeRef='final'),question=json.dumps(dict(canonicalCase=case))))],
            executions=[dict(runId='src',toolCallId='final',toolName='procurement_recommendation_finalize',result=dict(content=json.dumps(snapshot))),dict(runId='rfq',toolCallId='write',toolName='procurement_create_rfq',request=copy.deepcopy(request))],
            approvals=[dict(runId='rfq',status='APPROVED',toolCallRequest=request)],
            mcp=[dict(request=dict(toolName='mcp.erpnext.procurement_create_rfq',arguments=dict(request=dto)),result=dict(success=True,content=json.dumps(dict(record=dict(id='RFQ-1',requestDigest='digest')))))])
        after=dict(rfqs=[dict(name='RFQ-1',custom_procurement_key='key',custom_request_digest='digest',items=[dict(material_request='MR-A',material_request_item='MR-LINE')])])
        return raw,after
    def test_linked_chain(self):self.assertTrue(requisition_link_consistency(*self.fixture()))
    def test_missing_identity(self):
        r,a=self.fixture();del r['stageRuns'][0]['request']['metadata']['workflowCanonicalRequisition'];self.assertFalse(requisition_link_consistency(r,a))
    def test_legacy_missing_link_is_rejected(self):
        r,a=self.fixture();del r['approvals'][0]['toolCallRequest']['arguments']['requisitionId'];a['rfqs'][0]['items'][0]['material_request']=None;self.assertFalse(requisition_link_consistency(r,a))
    def test_erp_missing_link(self):
        r,a=self.fixture();a['rfqs'][0]['items'][0]['material_request']=None;self.assertFalse(requisition_link_consistency(r,a))
    def test_recommendation_mismatch(self):
        r,a=self.fixture();s=json.loads(r['executions'][0]['result']['content']);s['canonicalRequisitionId']='MR-B';r['executions'][0]['result']['content']=json.dumps(s);self.assertFalse(requisition_link_consistency(r,a))
    def test_canonical_request_tampering(self):
        r,a=self.fixture();r['executions'][1]['request']['arguments']['requisitionId']='MR-B';self.assertFalse(requisition_link_consistency(r,a))
    def test_erp_dto_tampering(self):
        r,a=self.fixture();r['mcp'][0]['request']['arguments']['request']['requisitionId']='MR-B';self.assertFalse(requisition_link_consistency(r,a))
    def test_missing_snapshot_fails_verification(self):
        r,a=self.fixture();self.assertFalse(requisition_link_consistency(r))
    def test_waiting_approval_does_not_require_erp(self):
        r,a=self.fixture();r['mcp']=[];r['executions']=r['executions'][:1];r['approvals'][0]['status']='REQUESTED';self.assertTrue(requisition_link_consistency(r))

if __name__=='__main__':unittest.main()
