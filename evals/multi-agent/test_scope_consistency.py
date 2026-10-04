import unittest
from scope_consistency import check,finish

class ScopeConsistencyTests(unittest.TestCase):
    def raw(self):
        return dict(result=dict(runId='root',serverFacts=dict(rootExecutionSummary=dict(scope='ROOT_GLOBAL',rootRunId='root',summaryRef='root-execution-summary:root',caseMutated=False,caseMutationCount=0,currentCaseVersion=-1,rfqCreated=False,rfqCreationCount=0))),runs=[],executions=[],case=None)
    def test_server_facts_alone_cannot_approve_prose(self):
        result=check(self.raw());self.assertEqual('PENDING_PROSE_REVIEW',result['status'])
        self.assertEqual('FAIL',finish(result,'FAIL','Explanation contradicts committed store state')['status'])
    def test_structured_mismatch_cannot_be_hidden_by_prose(self):
        raw=self.raw();raw['result']['serverFacts']['rootExecutionSummary']['caseMutated']=True
        self.assertEqual('FAIL',finish(check(raw),'PASS','Text inspected')['status'])
    def test_missing_server_summary_is_rejected(self):
        raw=self.raw();raw['result'].pop('serverFacts');self.assertEqual('FAIL',check(raw)['structured_status'])
    def test_explicit_consistent_review_passes(self):
        self.assertEqual('PASS',finish(check(self.raw()),'PASS','No mutations; text makes no committed write claim')['status'])

if __name__=='__main__':unittest.main()
