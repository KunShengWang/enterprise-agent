package com.agent.platform.multiagent;

import java.time.Instant;
import java.util.Map;

public record MultiAgentMessage(
        MultiAgentRole role,
        String taskId,
        String content,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public MultiAgentMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
