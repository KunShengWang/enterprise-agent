package com.agent.platform.tool;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        ToolRiskLevel riskLevel,
        Map<String, Object> metadata
) {

    public ToolDefinition {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
