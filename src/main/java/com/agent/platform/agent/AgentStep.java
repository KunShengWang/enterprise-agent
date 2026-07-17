package com.agent.platform.agent;

import java.util.Map;

public record AgentStep(
        String name,
        String status,
        String summary,
        Map<String, Object> metadata
) {

    public AgentStep {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentStep(String name, String status, String summary) {
        this(name, status, summary, Map.of());
    }
}
