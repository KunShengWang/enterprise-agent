package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;

/** Incident Specialist 的持久化输入检查点边界。 */
public interface AgentContinuationRuntime {

    AgentRuntimeResult runUntilInputCheckpoint(
            AgentRequest request,
            AgentExecutionProfile profile,
            AgentEventListener listener);

    AgentRuntimeResult continueWithInput(
            String runId,
            AgentFollowUpInput input,
            AgentEventListener listener);

    AgentRuntimeResult completeWaitingInput(String runId);
}
