import unittest
import test_acceptance_v11 as base_tests
from acceptance_v11 import evaluate, conclude
from scope_consistency import finish


class CandidateContractTests(unittest.TestCase):
    def fixture(self, validated=(), status='READY'):
        helper = base_tests.ApplicabilityTests()
        fixture = helper.fixture(child=True)
        def change(body):
            body['status'] = status
            body['analysis'].update(authority='runtime-validated-domain-result', status=status)
            body['assessment'].update(authority='runtime-validated-domain-result', status=status,
                                      validatedBlockers=list(validated), blockingReasons=list(validated), rejectedCandidateCount=1)
        helper.mutate_body(fixture, change)
        return fixture

    def result(self, fixture):
        row, consistency, _ = evaluate(*fixture)
        return conclude(row, finish(consistency, 'PASS', 'Offline fixture inspection'))

    def test_rejected_candidate_does_not_fail_valid_authoritative_result(self):
        self.assertEqual('PASS', self.result(self.fixture())['acceptance_status'])

    def test_supported_insufficient_enum_is_accepted(self):
        self.assertEqual('PASS', self.result(self.fixture(['BUDGET_INSUFFICIENT'], 'BLOCKED'))['acceptance_status'])

    def test_status_cannot_override_empty_authoritative_blockers(self):
        self.assertEqual('FAIL', self.result(self.fixture([], 'BLOCKED'))['acceptance_status'])

    def test_authority_label_alone_does_not_override_blocker_disagreement(self):
        f = self.fixture()
        base_tests.ApplicabilityTests().mutate_body(f, lambda b: b['assessment'].update(blockingReasons=['BUDGET_UNAVAILABLE']))
        self.assertEqual('FAIL', self.result(f)['acceptance_status'])


if __name__ == '__main__':
    unittest.main()
