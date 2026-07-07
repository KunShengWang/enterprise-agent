package com.agent.platform.stream;

import java.time.Instant;
import java.util.Map;

public record AgentStreamEvent(
        String eventId,
        String traceId,
        String conversationId,
        String type,
        String content,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public AgentStreamEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
