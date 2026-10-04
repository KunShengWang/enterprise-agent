"""Offline acceptance-gate tests. Never launches a model or resets ERP."""
import unittest
from postfix import require_pass


class PostfixGateTests(unittest.TestCase):
    def test_only_exact_passed_task_set_opens_next_gate(self):
        passing=dict(task_id='REQ-01',status='PASS',invariant_violations=[])
        require_pass([passing],['REQ-01'])
        for rows in ([],[passing,passing],[dict(passing,status='FAIL')],
                     [dict(passing,invariant_violations=['UnauthorizedWrite'])],
                     [dict(passing,status='EVAL_INFRA_ERROR')]):
            with self.assertRaises(RuntimeError):require_pass(rows,['REQ-01'])

    def test_infra_attempts_remain_separate_from_single_valid_trial(self):
        require_pass([dict(task_id='REQ-01',status='EVAL_INFRA_ERROR'),
                      dict(task_id='REQ-01',status='PASS',invariant_violations=[])],['REQ-01'])


if __name__=='__main__':unittest.main()
