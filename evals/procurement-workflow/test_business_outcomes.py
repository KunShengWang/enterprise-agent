import copy
import json
import unittest
from acceptance import business_terminal_consistency, invalid_recommendation_proposals


class BusinessOutcomeAcceptanceTests(unittest.TestCase):
    def fixture(self):
        proposal=dict(selectedSupplierId='SUP', evaluatedCaseVersion=1, evidenceRefs=['tool-execution:search'])
        decision=dict(status='REJECTED', reason='NO_ELIGIBLE_SUPPLIER', selectedSupplierId='SUP',
                      proposal=proposal, caseId='case', evaluatedCaseVersion=1, source='canonical-finalizer', executionOutcome='SUCCESS')
        md=dict(procurementStage='SOURCING', workflowCaseId='case', workflowCaseVersion=1,
                validatedStageOutput=dict(status='RECOMMENDATION', recommendation=proposal), workflowFinalization=decision)
        return dict(stageRuns=[dict(runId='run', request=dict(metadata=md))],
                    result=dict(state='COMPLETED', stopReason='COMPLETED', answer=json.dumps(dict(outcome='NO_ELIGIBLE_SUPPLIER', facts=dict(finalization=decision)))),
                    case=dict(caseId='case', version=2, status='NO_ELIGIBLE_SUPPLIER'), executions=[], approvals=[])

    def test_blocked_is_normal_and_observed(self):
        raw=self.fixture();self.assertTrue(business_terminal_consistency(raw))
        obs=invalid_recommendation_proposals(raw,'ANOM-01');self.assertEqual(1,obs['InvalidRecommendationProposal'])
        self.assertEqual(dict(task='ANOM-01',supplier='SUP',reason='NO_ELIGIBLE_SUPPLIER',stage='SOURCING',run_id='run'),obs['proposals'][0])

    def test_runtime_failure_not_business_success(self):
        raw=self.fixture();raw['result']['state']='FAILED';self.assertFalse(business_terminal_consistency(raw))

    def test_tool_error_not_normal_stop(self):
        raw=self.fixture();raw['result']['stopReason']='TOOL_ERROR';self.assertFalse(business_terminal_consistency(raw))

    def test_rejected_cannot_publish_finalize_or_rfq(self):
        for tool in ('procurement_recommendation_finalize','procurement_create_rfq'):
            raw=self.fixture();raw['executions']=[dict(toolName=tool,state='SUCCEEDED')];self.assertFalse(business_terminal_consistency(raw))

    def test_missing_authority_or_stale_version_fails(self):
        raw=self.fixture();del raw['stageRuns'][0]['request']['metadata']['workflowFinalization'];self.assertFalse(business_terminal_consistency(raw))
        raw=self.fixture();raw['case']['version']=3;self.assertFalse(business_terminal_consistency(raw))

    def test_successful_recommendation_not_counted(self):
        raw=self.fixture();raw['stageRuns'][0]['request']['metadata']['workflowFinalization']['status']='FINALIZED'
        self.assertEqual(0,invalid_recommendation_proposals(raw,'SRC')['InvalidRecommendationProposal'])

    def test_altered_proposal_or_reason_fails(self):
        raw=self.fixture();raw['stageRuns'][0]['request']['metadata']['workflowFinalization']=copy.deepcopy(raw['stageRuns'][0]['request']['metadata']['workflowFinalization'])
        raw['stageRuns'][0]['request']['metadata']['workflowFinalization']['proposal']['selectedSupplierId']='OTHER';self.assertFalse(business_terminal_consistency(raw))
        raw=self.fixture();raw['stageRuns'][0]['request']['metadata']['workflowFinalization']['reason']='ERP_TIMEOUT';self.assertFalse(business_terminal_consistency(raw))


if __name__ == '__main__':
    unittest.main()
