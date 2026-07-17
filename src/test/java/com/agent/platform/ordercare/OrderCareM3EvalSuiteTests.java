package com.agent.platform.ordercare;

import com.agent.platform.eval.EvalCase;
import com.agent.platform.eval.OrderCareM3EvalSuite;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OrderCareM3EvalSuiteTests {

    private final OrderCareM3EvalSuite suite = new OrderCareM3EvalSuite();

    @Test
    void definesTwentyCasesAcrossAllInterviewStrongGroups() {
        List<EvalCase> cases = suite.cases();
        Set<String> types = cases.stream()
                .map(item -> String.valueOf(item.metadata().get("ordercareCaseType")))
                .collect(Collectors.toSet());

        assertEquals(20, cases.size());
        assertEquals(20, cases.stream().map(EvalCase::id).distinct().count());
        assertTrue(types.containsAll(Set.of(
                "IDENTIFIER", "CLARIFICATION", "DIAGNOSIS", "HITL",
                "SAFETY", "RECOVERY", "ADVERSARIAL"
        )));
        assertTrue(cases.stream().anyMatch(item -> item.expectedTools()
                .contains(OrderCareToolCatalog.RECOVERY_EXECUTE)));
        assertTrue(cases.stream().filter(item -> "RECOVERY".equals(
                item.metadata().get("ordercareCaseType"))).count() >= 3);
        assertTrue(cases.stream().allMatch(item -> "ordercare-floworder-v1".equals(
                item.metadata().get("scenarioId"))));
    }
}
