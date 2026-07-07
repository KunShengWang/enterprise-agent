package com.agent.platform.multiagent;

import java.util.Map;

public record MultiAgentTask(
        String taskId,
        MultiAgentRole role,
        String instruction,
        Map<String, Object> metadata
) {

    public MultiAgentTask {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
