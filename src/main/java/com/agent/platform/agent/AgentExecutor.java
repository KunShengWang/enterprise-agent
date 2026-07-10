package com.agent.platform.agent;

public interface AgentExecutor {

    AgentResponse execute(AgentRequest request);

    default AgentResponse resume(String runId) {
        throw new UnsupportedOperationException("agent run resume is not supported");
    }
}
