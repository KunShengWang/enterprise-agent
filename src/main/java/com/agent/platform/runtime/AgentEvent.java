package com.agent.platform.runtime;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String eventId,
        String runId,
        String sessionId,
        long sequence,
        AgentEventType type,
        String content,
        Map<String, Object> payload,
        Instant createdAt
) {

    public AgentEvent {
        content = content == null ? "" : content;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
