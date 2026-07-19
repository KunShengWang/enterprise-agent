package com.agent.platform.workbench.model;

import java.util.Map;

public record WorkEventDraft(
        String sourceEventId,
        WorkEventType eventType,
        String phase,
        String summary,
        Map<String, Object> payload,
        String causationId
) {

    public WorkEventDraft {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId must not be blank");
        }
        sourceEventId = sourceEventId.trim();
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        phase = phase == null ? "" : phase.trim();
        summary = summary == null ? "" : summary.trim();
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        causationId = causationId == null ? "" : causationId.trim();
    }
}
