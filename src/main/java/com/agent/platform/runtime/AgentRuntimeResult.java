package com.agent.platform.runtime;

import java.util.List;

public record AgentRuntimeResult(
        String runId,
        String sessionId,
        AgentRunState state,
        AgentStopReason stopReason,
        String answer,
        String approvalId,
        AgentRunBudgetSnapshot budget,
        List<AgentEvent> events
) {

    public AgentRuntimeResult {
        answer = answer == null ? "" : answer;
        approvalId = approvalId == null ? "" : approvalId;
        events = events == null ? List.of() : List.copyOf(events);
    }
}
