package com.agent.platform.runtime;

import com.agent.platform.tool.ToolDefinition;

import java.util.List;
import java.util.Optional;

public interface AgentCapabilityRegistry {

    List<ToolDefinition> listCapabilities();

    Optional<ToolDefinition> findCapability(String name);
}
