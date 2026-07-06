package com.agent.platform.eval;

import com.agent.platform.agent.AgentRunStatus;

import java.time.Instant;
import java.util.List;

public record AgentRunEvalEvent(
        String traceId,
        String conversationId,
        AgentRunStatus status,
        List<String> usedTools,
        boolean usedRag,
        boolean blockedByGuardrail,
        Instant createdAt
) {

    public AgentRunEvalEvent {
        usedTools = usedTools == null ? List.of() : List.copyOf(usedTools);
    }
}
