package com.agent.platform.agent;

public record AgentStep(
        String name,
        String status,
        String summary
) {
}
