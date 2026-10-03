package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;

/** Domain-independent run boundary. The existing runtime remains the sole loop implementation. */
public interface AgentRunner {
    AgentRuntimeResult run(AgentRequest request, AgentEventListener listener);
    AgentRuntimeResult run(AgentRequest request, AgentExecutionProfile profile, AgentEventListener listener);
    AgentRuntimeResult resume(String runId, AgentEventListener listener);
    boolean cancel(String runId);
    boolean pause(String runId);
}
