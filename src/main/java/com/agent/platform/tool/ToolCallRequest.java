package com.agent.platform.tool;

import java.util.Map;

public record ToolCallRequest(
        String toolName,
        String requestId,
        Map<String, Object> arguments
) {

    public ToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
