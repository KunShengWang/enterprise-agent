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
        List<AgentEvent> events,
        java.util.Map<String,Object> serverFacts
) {

    public AgentRuntimeResult(String runId,String sessionId,AgentRunState state,AgentStopReason stopReason,
                              String answer,String approvalId,AgentRunBudgetSnapshot budget,List<AgentEvent> events){
        this(runId,sessionId,state,stopReason,answer,approvalId,budget,events,java.util.Map.of());
    }

    public AgentRuntimeResult {
        answer = answer == null ? "" : answer;
        approvalId = approvalId == null ? "" : approvalId;
        events = events == null ? List.of() : List.copyOf(events);
        serverFacts = serverFacts == null ? java.util.Map.of() : java.util.Map.copyOf(serverFacts);
    }

    public RunStatus status() {
        return RunStatus.from(state, stopReason);
    }
}
