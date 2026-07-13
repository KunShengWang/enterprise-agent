package com.agent.platform.agent;

public interface AgentExecutor {

    /**
     * 执行一次新的 Agent Run。
     *
     * <p>Controller 只依赖这个接口，不关心底层具体使用哪个执行器。
     * 当前 Spring 容器会优先注入标记了 {@code @Primary} 的 V1AgentExecutor。</p>
     */
    AgentResponse execute(AgentRequest request);

    /**
     * 从已经持久化的暂停点恢复一次 Agent Run。
     *
     * <p>普通执行器可以不支持恢复；V1AgentExecutor 会覆盖该方法，
     * 用于高风险工具通过人工审批后的继续执行。</p>
     */
    default AgentResponse resume(String runId) {
        throw new UnsupportedOperationException("agent run resume is not supported");
    }
}
