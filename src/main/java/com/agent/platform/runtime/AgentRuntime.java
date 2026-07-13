package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;

public interface AgentRuntime {

    AgentRuntimeResult run(AgentRequest request, AgentEventListener listener);

    AgentRuntimeResult resume(String runId, AgentEventListener listener);

    default AgentRuntimeResult run(AgentRequest request) {
        return run(request, AgentEventListener.NOOP);
    }

    default AgentRuntimeResult resume(String runId) {
        return resume(runId, AgentEventListener.NOOP);
    }
}
