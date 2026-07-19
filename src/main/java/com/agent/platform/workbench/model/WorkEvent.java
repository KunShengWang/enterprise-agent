package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

public record WorkEvent(
        String eventId,
        String workItemId,
        long sequence,
        String sourceType,
        String sourceId,
        String sourceEventId,
        Long sourceSequence,
        WorkEventType eventType,
        String phase,
        String summary,
        Map<String, Object> payload,
        String correlationId,
        String causationId,
        Instant sourceCreatedAt,
        Instant projectedAt
) {

    public WorkEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        phase = phase == null ? "" : phase;
        summary = summary == null ? "" : summary;
        causationId = causationId == null ? "" : causationId;
    }
}
