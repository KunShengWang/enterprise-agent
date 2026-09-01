package com.agent.platform.runtime;

import java.util.Set;

/**
 * Runtime 内部可信的执行配置；不会从用户请求 metadata 反序列化。
 */
public record AgentExecutionProfile(
        String name,// 执行配置名称
        String systemPrompt,// 系统提示词
        Set<String> allowedCapabilities,// agent 能使用的工具
        AgentRunLimits limits,// agent 运行时的限制条件
        boolean longTermMemoryEnabled// 启用长期记忆读写、recall、User Profile 和 memory_context
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
