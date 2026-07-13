package com.agent.platform.agent;

public interface AgentExecutor {

    /**
     * 执行一次新的 Agent Run。
     *
     * <p>Controller 只依赖这个接口，不关心底层具体使用哪个执行器。
     * 当前 Spring 容器会优先注入基于统一 AgentRuntime 的 RuntimeAgentExecutor。</p>
     */
    AgentResponse execute(AgentRequest request);

    /**
     * 从已经持久化的暂停点恢复一次 Agent Run。
     *
     * <p>普通执行器可以不支持恢复；RuntimeAgentExecutor 会把恢复请求交给同一个
     * AgentRuntime，用于高风险工具通过人工审批后的继续执行。</p>
     */
    default AgentResponse resume(String runId) {
        throw new UnsupportedOperationException("agent run resume is not supported");
    }
}
