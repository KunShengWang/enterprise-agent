package com.agent.platform.runtime;

import java.util.Set;

/**
 * Runtime 内部可信的执行配置；不会从用户请求 metadata 反序列化。
 */
public record AgentExecutionProfile(
        String name,
        String systemPrompt,
        Set<String> allowedCapabilities,
        AgentRunLimits limits,
        boolean longTermMemoryEnabled
) {

    public AgentExecutionProfile {
        name = name == null || name.isBlank() ? "agent" : name.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        if (limits == null) {
            throw new IllegalArgumentException("execution profile limits must not be null");
        }
    }

    public boolean allows(String capabilityName) {
        return capabilityName != null && allowedCapabilities.contains(capabilityName);
    }
}
