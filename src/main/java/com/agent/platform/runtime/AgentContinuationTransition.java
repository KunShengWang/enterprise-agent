package com.agent.platform.runtime;

/** JDBC 事务内同时提交 Run、Timeline 消息和 Runtime 事件后的结果。 */
public record AgentContinuationTransition(
        AgentRunRecord run,
        AgentMessage message,
        AgentEvent event
) {}
