import copy
import json
import unittest
from acceptance import clarification_causality


class ClarificationCausalityTests(unittest.TestCase):
    def fixture(self):
        return dict(result=dict(answer=json.dumps(dict(outcome='NEEDS_USER_INPUT',facts=dict(missing=['quantity'])))),
                    case=dict(state=dict(productDescription='SKU')),executions=[],stageRuns=[])

    def failure(self):
        return dict(runId='r',toolName='get_item',state='FAILED',updatedAt='2026-10-05T00:00:00Z',
                    request=dict(arguments=dict(id='SKU')),result=dict(success=False,metadata=dict(_harnessOutcome='RETRYABLE_ERROR')))

    def test_quantity_missing(self):self.assertTrue(clarification_causality(self.fixture()))
    def test_failure_with_missing_quantity_is_not_clarification(self):
        r=self.fixture();r['executions']=[self.failure()];self.assertFalse(clarification_causality(r))
    def test_failure_with_supplied_quantity_is_not_clarification(self):
        r=self.fixture();r['case']['state']['quantity']=20;r['executions']=[self.failure()];self.assertFalse(clarification_causality(r))
    def test_recovered_read_still_allows_real_user_missing(self):
        r=self.fixture();f=self.failure();s=copy.deepcopy(f);s.update(state='SUCCEEDED',updatedAt='2026-10-05T00:00:01Z',result=dict(success=True));r['executions']=[f,s]
        self.assertTrue(clarification_causality(r))
        s['request']['arguments']['id']='OTHER';self.assertFalse(clarification_causality(r))
    def test_canonical_missing_without_user_missing_fails(self):
        r=self.fixture();r['result']['answer']=json.dumps(dict(outcome='NEEDS_USER_INPUT',facts=dict(missing=['canonicalItem'])))
        self.assertFalse(clarification_causality(r))
    def test_known_business_blocker(self):
        r=self.fixture();r['result']['answer']=json.dumps(dict(outcome='NEEDS_USER_INPUT',facts=dict(requisition=dict(validatedBlockers=['DUPLICATE_REQUEST']))))
        self.assertTrue(clarification_causality(r))


if __name__ == '__main__':unittest.main()
