package com.agent.platform.trace;

import java.time.Instant;
import java.util.Map;

public record TraceReplayEvent(
        int sequence,
        Instant occurredAt,
        String eventType,
        String summary,
        Map<String, Object> payload
) {

    public TraceReplayEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
