package com.agent.platform.ordercare;

import com.agent.platform.eval.EvalCase;
import com.agent.platform.eval.OrderCareM2EvalSuite;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCareM2EvalSuiteTests {

    private final OrderCareM2EvalSuite suite = new OrderCareM2EvalSuite();

    @Test
    void definesTenBusinessCasesWithHitlAndWriteBoundaryCoverage() {
        List<EvalCase> cases = suite.cases();

        assertEquals(10, cases.size());
        assertEquals(10, cases.stream().map(EvalCase::id).distinct().count());
        assertTrue(cases.stream().anyMatch(item -> item.expectedTools().contains(OrderCareToolCatalog.RECOVERY_PREVIEW)));
        assertTrue(cases.stream().anyMatch(item -> item.expectedTools().contains(OrderCareToolCatalog.RECOVERY_EXECUTE)));
        assertTrue(cases.stream().filter(item -> "WRITE_BOUNDARY".equals(item.metadata().get("ordercareCaseType"))).count() >= 2);
        assertTrue(cases.stream().allMatch(item -> "ordercare-floworder-v1".equals(item.metadata().get("scenarioId"))));
    }
}
