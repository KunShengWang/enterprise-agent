package com.agent.platform.workbench.budget;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkbenchBudgetEvalSuiteTests {

    @TestFactory
    List<DynamicTest> evaluatesFifteenBoundaryAndCumulativeBudgetCases() {
        BudgetLimit maximum = new BudgetLimit(5, 1_000, 3, 10_000, 5);
        return cases().stream().map(value -> DynamicTest.dynamicTest(value.id(), () -> {
            boolean allowed = value.consumed().plus(value.reserved()).plus(value.requested())
                    .fitsWithin(maximum);
            assertEquals(value.expectedAllowed(), allowed);
        })).toList();
    }

    private List<Case> cases() {
        BudgetLimit zero = amount(0, 0, 0, 0, 0);
        return List.of(
                allowed("model-exact", zero, zero, amount(5, 0, 0, 0, 0)),
                denied("model-over", zero, zero, amount(6, 0, 0, 0, 0)),
                allowed("token-exact", zero, zero, amount(0, 1_000, 0, 0, 0)),
                denied("token-over", zero, zero, amount(0, 1_001, 0, 0, 0)),
                allowed("tool-exact", zero, zero, amount(0, 0, 3, 0, 0)),
                denied("tool-over", zero, zero, amount(0, 0, 4, 0, 0)),
                allowed("duration-exact", zero, zero, amount(0, 0, 0, 10_000, 0)),
                denied("duration-over", zero, zero, amount(0, 0, 0, 10_001, 0)),
                allowed("cost-exact", zero, zero, amount(0, 0, 0, 0, 5)),
                denied("cost-over", zero, zero, amount(0, 0, 0, 0, 5.01)),
                allowed("consumed-plus-request", amount(1, 200, 1, 1_000, 1), zero,
                        amount(4, 800, 2, 9_000, 4)),
                denied("consumed-token-over", amount(1, 800, 0, 0, 0), zero,
                        amount(1, 201, 0, 0, 0)),
                allowed("reserved-plus-request", zero, amount(2, 400, 1, 2_000, 2),
                        amount(3, 600, 2, 8_000, 3)),
                denied("reserved-model-over", zero, amount(4, 100, 0, 0, 0),
                        amount(2, 100, 0, 0, 0)),
                denied("mixed-cost-over", amount(1, 100, 1, 1_000, 2),
                        amount(1, 100, 1, 1_000, 2), amount(1, 100, 1, 1_000, 1.01))
        );
    }

    private Case allowed(String id, BudgetLimit consumed, BudgetLimit reserved, BudgetLimit requested) {
        return new Case(id, consumed, reserved, requested, true);
    }
    private Case denied(String id, BudgetLimit consumed, BudgetLimit reserved, BudgetLimit requested) {
        return new Case(id, consumed, reserved, requested, false);
    }
    private BudgetLimit amount(int models, long tokens, int tools, long duration, double cost) {
        return new BudgetLimit(models, tokens, tools, duration, cost);
    }
    private record Case(String id, BudgetLimit consumed, BudgetLimit reserved,
                        BudgetLimit requested, boolean expectedAllowed) { }
}
