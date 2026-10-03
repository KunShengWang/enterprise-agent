import importlib.util
import json
from pathlib import Path
import unittest

SPEC=importlib.util.spec_from_file_location('harness_runner',Path(__file__).with_name('run.py'))
runner=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(runner)

class RunnerTests(unittest.TestCase):
    def test_smoke_is_fixed_and_dev_only(self):
        tasks={runner.legacy.read(p)['task_id']:runner.legacy.read(p) for p in (runner.ROOT/'evals/procurement-v2/tasks').rglob('*.json')}
        self.assertEqual(9,len(runner.SMOKE))
        self.assertEqual(9,len(set(runner.SMOKE)))
        self.assertTrue(all(tasks[t]['split']=='dev' for t in runner.SMOKE))
        self.assertEqual(28,sum(t['split']=='dev' for t in tasks.values()))

    def test_metrics_keep_waiting_distinct_from_tool_error(self):
        raw={'result':{'stopReason':'WAITING_APPROVAL','budget':{'turns':3,'toolCalls':2}},
             'taskLatencyMs':10,'events':[{'type':'APPROVAL_REQUIRED','payload':{'outcome':'APPROVAL_REQUIRED'}},
             {'type':'TOOL_COMPLETED','payload':{'outcome':'SUCCESS','durationMs':2,'retryCount':0}}]}
        metrics=runner.runtime_metrics(raw)
        self.assertEqual({'SUCCESS':1,'APPROVAL_REQUIRED':1},metrics['outcome_counts'])
        self.assertFalse(metrics['budget_exhausted'])
        self.assertEqual(10,metrics['run_duration_ms'])

if __name__=='__main__': unittest.main()
