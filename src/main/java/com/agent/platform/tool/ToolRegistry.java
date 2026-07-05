package com.agent.platform.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    List<ToolDefinition> listTools();

    Optional<ToolDefinition> findTool(String toolName);
}
