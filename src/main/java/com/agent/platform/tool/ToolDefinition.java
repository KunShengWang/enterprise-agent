package com.agent.platform.tool;

import java.util.Map;

public record ToolDefinition(
        String name,// 工具名称
        String description,// 工具描述
        String inputSchema,// 工具需要的参数
        ToolRiskLevel riskLevel,// 工具风险等级
        Map<String, Object> metadata// 元数据
) {

    public ToolDefinition {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
