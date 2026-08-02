package com.agent.platform.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    /**
     * 列出全部工具
     */
    List<ToolDefinition> listTools();

    /**
     * 根据工具名称寻找工具
     */
    Optional<ToolDefinition> findTool(String toolName);
}
