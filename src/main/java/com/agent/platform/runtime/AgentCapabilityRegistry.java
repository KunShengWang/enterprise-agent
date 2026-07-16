package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;

import java.util.List;
import java.util.Optional;

public interface AgentCapabilityRegistry {

    /**
     * 列出 agent 的能力，也就是 agent 能访问的工具
     * 包括本地定义的工具和 mcp 提供的工具
     */
    List<ToolDefinition> listCapabilities();

    Optional<ToolDefinition> findCapability(String name);
}
