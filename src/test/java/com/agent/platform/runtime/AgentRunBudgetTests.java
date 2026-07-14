package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunBudgetTests {

    @Test
    void restoresConsumedBudgetWithoutResettingDeadline() {
        AgentRunLimits limits = new AgentRunLimits(8, 2, 3, 1_000, 500, 1.0, 60_000);
        Instant startedAt = Instant.now().minusSeconds(10);
        Instant deadline = Instant.now().plusSeconds(50);
        AgentRunBudgetSnapshot persisted = new AgentRunBudgetSnapshot(
                2, 2, 1, 300, 40, 0.25, startedAt, deadline, false
        );

        AgentRunBudget restored = new AgentRunBudget(limits, persisted);

        AgentRunBudgetSnapshot restoredSnapshot = restored.snapshot();
        assertEquals(persisted.turns(), restoredSnapshot.turns());
        assertEquals(persisted.modelCalls(), restoredSnapshot.modelCalls());
        assertEquals(persisted.deadline(), restoredSnapshot.deadline());
        assertEquals(AgentStopReason.MODEL_BUDGET_EXHAUSTED, restored.beforeModelCall().orElseThrow());
    }

    @Test
    void pausedExecutionBudgetSurvivesApprovalWaitAndGetsANewDeadlineOnResume() {
        AgentRunLimits limits = new AgentRunLimits(8, 4, 3, 1_000, 500, 1.0, 60_000);
        AgentRunBudgetSnapshot pausedSnapshot = new AgentRunBudgetSnapshot(
                2, 1, 0, 100, 20, 0.01,
                Instant.now().minusSeconds(3_600),
                Instant.now().minusSeconds(3_500),
                false,
                30_000,
                true
        );

        AgentRunBudget restored = new AgentRunBudget(limits, pausedSnapshot);

        assertTrue(restored.beforeTurn().isEmpty());
        assertTrue(restored.snapshot().executionPaused());
        restored.resumeExecution();
        AgentRunBudgetSnapshot resumed = restored.snapshot();
        assertTrue(!resumed.executionPaused());
        assertTrue(resumed.deadline().isAfter(Instant.now().plusSeconds(25)));
        assertTrue(restored.beforeTurn().isEmpty());
    }

    @Test
    void waitingCheckpointRetainsTrustedProfileAndBudget() {
        AgentRunLimits limits = new AgentRunLimits(4, 4, 2, 1_000, 500, 1.0, 60_000);
        AgentExecutionProfile profile = new AgentExecutionProfile(
                "restricted-agent", "restricted prompt", Set.of("ticket_status"), limits, false
        );
        AgentRunBudgetSnapshot budget = new AgentRunBudget(limits).snapshot();
        AgentRunRecord record = AgentRunRecord.create(
                "run-1", "trace-1", "session-1",
                new AgentRequest("session-1", "user-1", "question", Map.of()),
                profile,
                budget
        );

        AgentRunRecord waiting = record.waitingForApproval(
                "approval-1", null, List.of(), List.of(), false, budget
        );

        assertSame(profile, waiting.executionProfile());
        assertEquals(budget, waiting.budgetSnapshot());
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state());
    }

    @Test
    void configuredTokenPricesAccumulateAndEnforceCostBudget() {
        AgentProperties properties = new AgentProperties();
        properties.getModelPricing().setInputPerMillionTokens(2.0);
        properties.getModelPricing().setOutputPerMillionTokens(8.0);
        properties.getModelPricing().setCacheReadPerMillionTokens(0.5);
        properties.getModelPricing().setCacheWritePerMillionTokens(1.0);
        ConfiguredLlmCostCalculator calculator = new ConfiguredLlmCostCalculator(properties);
        LlmUsage usage = new LlmUsage(1_000, 500, 1_500, 200, 100, "model", "provider");
        double cost = calculator.estimate(usage);
        AgentRunBudget budget = new AgentRunBudget(
                new AgentRunLimits(4, 4, 2, 10_000, 10_000, cost, 60_000)
        );

        budget.recordModelCall(usage, cost);

        assertEquals(0.0056, cost, 0.0000001);
        assertEquals(AgentStopReason.MODEL_BUDGET_EXHAUSTED, budget.beforeModelCall().orElseThrow());
    }
}
