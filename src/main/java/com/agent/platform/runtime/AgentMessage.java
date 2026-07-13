package com.agent.platform.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * 数据库中按 sequence 严格排序的 Agent 消息。
 */
public record AgentMessage(
        String messageId,
        String sessionId,
        String runId,
        long sequence,
        AgentMessageType type,
        String content,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> metadata,
        long estimatedTokens,
        Instant createdAt
) {

    public AgentMessage {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        content = content == null ? "" : content;
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    public boolean isToolCall() {
        return type == AgentMessageType.ASSISTANT_TOOL_CALL;
    }

    public boolean isToolResult() {
        return type == AgentMessageType.TOOL_RESULT;
    }
}
