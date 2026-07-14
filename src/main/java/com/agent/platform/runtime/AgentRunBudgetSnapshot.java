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
        boolean cancelled,
        long remainingExecutionMillis,
        boolean executionPaused
) {

    public AgentRunBudgetSnapshot(int turns,
                                  int modelCalls,
                                  int toolCalls,
                                  long inputTokens,
                                  long outputTokens,
                                  double estimatedCost,
                                  Instant startedAt,
                                  Instant deadline,
                                  boolean cancelled) {
        this(turns, modelCalls, toolCalls, inputTokens, outputTokens, estimatedCost,
                startedAt, deadline, cancelled, 0, false);
    }
}
