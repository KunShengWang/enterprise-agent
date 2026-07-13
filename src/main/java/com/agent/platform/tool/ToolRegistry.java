package com.agent.platform.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    /**
     * 寻找服务商的工具
     */
    List<ToolDefinition> listTools();

    /**
     * 根据工具名称寻找工具
     */
    Optional<ToolDefinition> findTool(String toolName);
}
