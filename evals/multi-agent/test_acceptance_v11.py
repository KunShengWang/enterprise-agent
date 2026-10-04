import json
import unittest
from acceptance_v11 import evaluate, conclude
from scope_consistency import finish


class ApplicabilityTests(unittest.TestCase):
    def fixture(self, outcome='clarify', child=False):
        summary = dict(scope='ROOT_GLOBAL', rootRunId='root', summaryRef='root-execution-summary:root', caseMutated=False,
                       caseMutationCount=0, currentCaseVersion=-1, rfqCreated=False, rfqCreationCount=0)
        root = dict(runId='root', request=dict(metadata={}), executionProfile=dict(name='procurement-supervisor-v1'), state='COMPLETED')
        raw = dict(result=dict(runId='root', serverFacts=dict(rootExecutionSummary=summary)), case=None, runs=[root], executions=[], models=[])
        if child:
            raw['runs'].append(dict(runId='child', request=dict(metadata={'_domainParentRunId': 'root', '_domainRootRunId': 'root'}, question=json.dumps(dict(businessRefs=dict(departmentId='V2-ALG'), priorEvidenceRefs=[]))), executionProfile=dict(name='procurement-requisition-agent-v1'), state='COMPLETED'))
            local = dict(scope='CHILD_LOCAL', childRunId='child', caseMutated=False, caseMutationCount=0, rfqCreated=False)
            body = dict(childRunId='child', childExecutionSummary=local, rootExecutionSummary=summary,
                        analysis=dict(status='READY', authority='validated-analysis-not-committed-state', evidenceRefs=['ref']),
                        evidenceLedger=[dict(evidenceRef='ref', sourceToolCallId='budget')],
                        proposals=dict(authority='uncommitted-model-proposal'), assessment=dict(blockingReasons=[], deferredChecks=[]))
            raw['executions'] = [dict(toolCallId='budget', toolName='mcp.erpnext.procurement_get_budget_status', runId='child', state='SUCCEEDED', request=dict(arguments=dict(departmentId='V2-ALG')), result=dict(success=True, metadata={}, content='{}')),
                dict(toolCallId='delegate', toolName='analyze_requisition', runId='root', state='SUCCEEDED', result=dict(success=True, metadata={}, content=json.dumps(body)))]
        return dict(expected=dict(outcome=outcome)), dict(status='PASS', invariant_violations=[]), raw

    def final(self, fixture):
        row, consistency, _ = evaluate(*fixture)
        return conclude(row, finish(consistency, 'PASS', 'Offline test fixture prose reviewed'))

    def mutate_body(self, fixture, operation):
        record = fixture[2]['executions'][-1]
        body = json.loads(record['result']['content']); operation(body); record['result']['content'] = json.dumps(body)

    def test_optional_child_not_called_is_na_and_pass(self):
        row = self.final(self.fixture())
        self.assertEqual('PASS', row['acceptance_status'])
        for name in ('canonical_id_binding', 'evidence_ledger', 'constrained_enum', 'child_execution_scope'):
            self.assertEqual('NOT_APPLICABLE', row['acceptance_checks'][name]['status'])
        self.assertEqual('PASS', row['acceptance_checks']['root_execution_scope']['status'])

    def test_old_per_task_coverage_failure_is_not_carried_forward(self):
        f = self.fixture(); f[1].update(acceptance_status='FAIL', acceptance_failures=['ACCEPTANCE_COVERAGE_MISSING'], coverage_required=['budget'])
        self.assertEqual('PASS', self.final(f)['acceptance_status'])

    def test_required_child_missing_still_fails(self):
        self.assertEqual('FAIL', self.final(self.fixture('recommend'))['acceptance_checks']['routing']['status'])

    def test_forbidden_child_called_still_fails(self):
        f = self.fixture(child=True); f[2]['runs'][1]['executionProfile']['name'] = 'procurement-rfq-agent-v1'
        self.assertEqual('FAIL', self.final(f)['acceptance_checks']['routing']['status'])

    def test_valid_executed_contract_passes(self):
        row = self.final(self.fixture(child=True)); self.assertEqual('PASS', row['acceptance_status'])
        self.assertEqual('PASS', row['acceptance_checks']['evidence_ledger']['status'])

    def test_child_ready_without_evidence_fails(self):
        f = self.fixture(child=True); self.mutate_body(f, lambda b: b['analysis'].update(evidenceRefs=[]))
        self.assertEqual('FAIL', self.final(f)['acceptance_checks']['evidence_ledger']['status'])

    def test_failed_tool_as_positive_evidence_fails(self):
        f = self.fixture(child=True); f[2]['executions'][0]['result']['success'] = False
        self.assertEqual('FAIL', self.final(f)['acceptance_checks']['evidence_ledger']['status'])

    def test_unresolved_evidence_ref_fails(self):
        f = self.fixture(child=True); self.mutate_body(f, lambda b: b.update(evidenceLedger=[]))
        self.assertEqual('FAIL', self.final(f)['acceptance_status'])

    def test_executed_canonical_mismatch_fails(self):
        f = self.fixture(child=True); f[2]['executions'][0]['request']['arguments']['departmentId'] = 'wrong'
        self.assertEqual('FAIL', self.final(f)['acceptance_checks']['canonical_id_binding']['status'])

    def test_executed_invalid_enum_fails(self):
        f = self.fixture(child=True); self.mutate_body(f, lambda b: b['assessment'].update(blockingReasons=['WRONG']))
        self.assertEqual('FAIL', self.final(f)['acceptance_checks']['constrained_enum']['status'])

    def test_no_child_cannot_disable_root_scope_check(self):
        f = self.fixture(); f[2]['result']['serverFacts']['rootExecutionSummary']['caseMutated'] = True
        self.assertEqual('FAIL', self.final(f)['acceptance_status'])

    def test_correct_metadata_cannot_hide_prose_failure(self):
        row, consistency, _ = evaluate(*self.fixture())
        result = conclude(row, finish(consistency, 'FAIL', 'Contradictory user-visible explanation'))
        self.assertEqual('FAIL', result['acceptance_status'])


if __name__ == '__main__':
    unittest.main()
