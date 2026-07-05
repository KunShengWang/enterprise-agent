package com.agent.platform.tool;

import java.util.Map;

public record ToolCallResult(
        String toolName,
        boolean success,
        String content,
        String errorMessage,
        Map<String, Object> metadata
) {

    public ToolCallResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
