package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

        assertEquals(persisted, restored.snapshot());
        assertEquals(AgentStopReason.MODEL_BUDGET_EXHAUSTED, restored.beforeModelCall().orElseThrow());
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
}
