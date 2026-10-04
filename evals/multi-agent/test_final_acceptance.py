"""Offline C1/C2 gate validation; never invokes models."""
import unittest
from final_acceptance import require_pass,audit

class FinalAcceptanceTests(unittest.TestCase):
    def test_gate_requires_exact_complete_successful_set(self):
        passing=dict(task_id='A',status='PASS',invariant_violations=[],acceptance_failures=[])
        require_pass([passing],['A'])
        for rows in ([],[passing,passing],[dict(passing,status='FAIL')],
                     [dict(passing,acceptance_failures=['CHILD_OUTPUT_INVALID'])],
                     [dict(passing,invariant_violations=['UnauthorizedWrite'])]):
            with self.assertRaises(RuntimeError):require_pass(rows,['A'])
    def test_successful_grader_cannot_hide_contract_error(self):
        raw=dict(executions=[dict(toolCallId='bad',toolName='analyze_requisition',result=dict(success=False,metadata=dict(errorType='CHILD_OUTPUT_INVALID')))])
        row=audit(dict(status='PASS',invariant_violations=[]),raw)
        self.assertIn('CHILD_OUTPUT_INVALID: bad',row['acceptance_failures'])
    def test_role_input_tokens_do_not_double_count_aggregate_budget(self):
        raw=dict(result=dict(runId='root'),models=[dict(request=dict(runId=r),turn=dict(usage=dict(promptTokens=n,completionTokens=1))) for r,n in [('root',10),('child',20)]])
        row=audit(dict(status='PASS',invariant_violations=[],input_tokens=35),raw)
        self.assertEqual(10,row['final_source_metrics']['root_input_tokens'])
        self.assertEqual(20,row['final_source_metrics']['child_input_tokens'])
        self.assertEqual(35,row['final_source_metrics']['total_provider_input_tokens'])

if __name__=='__main__':unittest.main()
