package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;

public interface AgentRuntime {

    AgentRuntimeResult run(AgentRequest request, AgentEventListener listener);

    AgentRuntimeResult resume(String runId, AgentEventListener listener);

    /** 持久化取消请求；同实例正在运行时同时触发本地预算取消。 */
    boolean cancel(String runId);

    default AgentRuntimeResult run(AgentRequest request) {
        return run(request, AgentEventListener.NOOP);
    }

    default AgentRuntimeResult resume(String runId) {
        return resume(runId, AgentEventListener.NOOP);
    }
}
