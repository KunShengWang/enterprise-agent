package com.agent.platform.eval;

import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCareM1EvalSuiteTests {

    @Test
    void providesEightBusinessCasesBoundToTrustedOrderCareScenario() {
        List<EvalCase> cases = new OrderCareM1EvalSuite().cases();

        assertEquals(8, cases.size());
        assertTrue(cases.stream().allMatch(evalCase ->
                AgentScenarioProfileResolver.ORDERCARE_FLOWORDER_V1.equals(
                        evalCase.metadata().get("scenarioId")
                )));
        assertTrue(cases.stream().filter(EvalCase::expectRag).count() >= 2);
        assertTrue(cases.stream().anyMatch(evalCase ->
                evalCase.expectedTools().contains(OrderCareToolCatalog.CASE_INSPECT)));
        assertTrue(cases.stream().anyMatch(evalCase ->
                evalCase.expectedTools().contains(DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH)));
        assertTrue(cases.stream().anyMatch(evalCase ->
                evalCase.forbiddenKeywords().contains("已经恢复成功")));
    }
}
