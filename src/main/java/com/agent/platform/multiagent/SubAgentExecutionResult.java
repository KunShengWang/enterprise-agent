package com.agent.platform.multiagent;

/**
 * 子 Agent 返回给父协调器的最小结果，不包含子会话完整消息和事件。
 */
public record SubAgentExecutionResult(
        String taskId,
        MultiAgentRole role,
        String childRunId,
        String childSessionId,
        String answer,
        MultiAgentMessage message
) {
}
