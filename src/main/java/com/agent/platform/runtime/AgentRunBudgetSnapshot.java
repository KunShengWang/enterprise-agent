package com.agent.platform.runtime;

import java.time.Instant;

public record AgentRunBudgetSnapshot(
        int turns,
        int modelCalls,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        double estimatedCost,
        Instant startedAt,
        Instant deadline,
        boolean cancelled
) {
}
