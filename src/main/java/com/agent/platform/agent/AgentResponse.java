package com.agent.platform.agent;

import com.agent.platform.trace.TraceSummary;

import java.util.List;

public record AgentResponse(
        String runId,
        String conversationId,
        AgentRunStatus status,
        String answer,
        String approvalId,
        List<AgentStep> steps,
        TraceSummary trace
) {

    public AgentResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        approvalId = approvalId == null ? "" : approvalId;
    }

    public AgentResponse(String conversationId,
                         AgentRunStatus status,
                         String answer,
                         List<AgentStep> steps,
                         TraceSummary trace) {
        this(trace == null ? "" : trace.traceId(), conversationId, status, answer, "", steps, trace);
    }
}
