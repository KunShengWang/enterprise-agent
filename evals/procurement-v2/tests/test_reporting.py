"""Accounting/model metadata checks; no live calls and no scoring changes."""
import sys
from pathlib import Path
import unittest
EVAL=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(EVAL/'runners'),str(EVAL/'graders')]
from reporting import identity, summarize, link_attempts
from test_grade import fixture
from grade import grade

class ReportingContracts(unittest.TestCase):
    def test_model_alias_change_warns_without_modifying_trial_status(self):
        raw=dict(controls={'effectiveModelOptions':{'model':'deepseek-chat'}},models=[{'turn':{'usage':{'model':'another-build','source':'provider'}}}])
        meta=identity(raw)
        self.assertEqual(meta['configured_model'],'deepseek-chat')
        self.assertEqual(meta['resolved_model'],'another-build')
        self.assertIn('MODEL_COMPARABILITY_WARNING',meta['model_comparability_warnings'])
        self.assertNotIn('status',meta)
    def test_unknown_model_is_not_invented(self):
        meta=identity({});self.assertIsNone(meta['resolved_model']);self.assertIsNone(meta['model_metadata_if_available']['exact_immutable_build'])
    def test_replacement_chain_and_excluded_infra_accounting(self):
        t,raw,w=fixture();valid=grade(t,raw,w,w)
        valid.update(trial_id='T-1',attempt_id='T-1-a3',artifact_directory='target/T-1-a3',artifact_status='complete',input_tokens=30,output_tokens=2,total_tokens=32)
        infra=[dict(task_id='T',trial_id='T-1',status='EVAL_INFRA_ERROR',attempt_id='T-1-a'+str(i),artifact_directory='target/T-1-a'+str(i),artifact_status='partial',tool_errors=99,total_tokens=999) for i in (1,2)]
        rows=infra+[valid];linked=link_attempts(rows)
        self.assertEqual(linked[0]['replacement_attempt_id'],'T-1-a2')
        self.assertEqual(linked[1]['replacement_attempt_id'],'T-1-a3')
        self.assertIsNone(linked[2]['replacement_attempt_id'])
        s=summarize(rows,1,1)
        self.assertEqual((s['total_execution_attempts'],s['valid_agent_trials'],s['infra_invalid_attempts'],s['replacement_attempts']),(3,1,2,2))
        self.assertEqual((s['complete_artifacts'],s['partial_artifacts']),(1,2))
        self.assertEqual(s['tool_errors'],0);self.assertEqual(s['metrics']['total_tokens']['sum'],32)
    def test_prevented_probes_separate_from_tool_errors(self):
        t,raw,w=fixture();r=grade(t,raw,w,w)
        r.update(trial_id='T-1',attempt_id='T-1-a1',artifact_directory='target/T-1-a1',artifact_status='complete',prevented_unsafe_attempts=dict(count=1,by_action={'stale_case':1}))
        s=summarize([r],1,1)
        self.assertEqual(s['PreventedUnsafeAttempt']['count'],1)
        self.assertFalse(s['PreventedUnsafeAttempt']['included_in_tool_error_rate']);self.assertEqual(s['tool_errors'],0)

if __name__=='__main__':unittest.main()
