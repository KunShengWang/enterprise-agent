package com.agent.platform.runtime;

import java.util.Map;

public record AgentEventDraft(
        AgentEventType type,
        String content,
        Map<String, Object> payload
) {

    public AgentEventDraft {
        if (type == null) {
            throw new IllegalArgumentException("event type must not be null");
        }
        content = content == null ? "" : content;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
