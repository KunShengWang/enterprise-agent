package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

public record ProjectedWorkEventDraft(
        String sourceType,
        String sourceId,
        String sourceEventId,
        long sourceSequence,
        WorkEventType eventType,
        String phase,
        String summary,
        Map<String, Object> payload,
        String correlationId,
        String causationId,
        Instant sourceCreatedAt
) {
    public ProjectedWorkEventDraft {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()
                || sourceEventId == null || sourceEventId.isBlank() || sourceSequence < 0
                || eventType == null || sourceCreatedAt == null) {
            throw new IllegalArgumentException("projected source identity, sequence, type and time are required");
        }
        sourceType = sourceType.trim();
        sourceId = sourceId.trim();
        sourceEventId = sourceEventId.trim();
        phase = phase == null ? "" : phase.trim();
        summary = summary == null ? "" : summary.trim();
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        correlationId = correlationId == null ? "" : correlationId.trim();
        causationId = causationId == null ? "" : causationId.trim();
    }
}
