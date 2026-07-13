package com.agent.platform.runtime;

import java.util.Map;

public record AgentToolCall(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        String reason
) {

    public AgentToolCall {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        reason = reason == null ? "" : reason;
    }
}
