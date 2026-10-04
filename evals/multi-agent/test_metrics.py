"""Offline tests for additive routing and hierarchical accounting; no model calls."""
import unittest
from metrics import multi_agent_metrics, routing


class MetricsTests(unittest.TestCase):
    def test_routing_requires_capability_not_sequence(self):
        self.assertEqual(routing({'expected': {'outcome': 'recommend'}}),
                         dict(required=['source_suppliers'], forbidden=['prepare_rfq'], optional=['analyze_requisition']))
        self.assertEqual(set(routing({'expected': {'outcome': 'rfq'}})['required']), {'source_suppliers', 'prepare_rfq'})
        self.assertEqual(set(routing({'expected': {'outcome': 'clarify'}})['forbidden']), {'source_suppliers', 'prepare_rfq'})

    def test_child_tokens_are_not_double_counted_from_root_budget(self):
        def run(id, agent, parent=''):
            return dict(runId=id, request=dict(metadata={'_domainParentRunId': parent}),
                        executionProfile=dict(name=agent), budgetSnapshot=dict(turns=2, toolCalls=1), state='COMPLETED')
        def model(id, prompt, completion, tools):
            return dict(request=dict(runId=id, tools=[{}]*tools), turn=dict(usage=dict(promptTokens=prompt, totalTokens=prompt+completion)))
        raw=dict(result=dict(runId='root'), runs=[run('root','procurement-supervisor-v1'),run('child','procurement-sourcing-agent-v1','root')],
                 models=[model('root',10,2,4),model('child',100,4,10)],executions=[])
        metrics=multi_agent_metrics({'expected':{'outcome':'recommend'}},raw)
        self.assertEqual(metrics['supervisor_tokens'],12)
        self.assertEqual(metrics['child_tokens'],104)
        self.assertEqual(metrics['overall_max_context'],100)
        self.assertEqual(metrics['root_peak_context'],10)
        self.assertEqual(metrics['routing_errors'],[])
        self.assertIsNone(metrics['unnecessary_subagent_calls'])
        metrics=multi_agent_metrics({'expected':{'outcome':'clarify'}},raw)
        self.assertEqual(metrics['routing_errors'],['source_suppliers'])


if __name__ == '__main__':
    unittest.main()
