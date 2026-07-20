package com.agent.platform.workbench.web;

import java.time.Instant;
import java.util.Map;

public record UnifiedWorkStreamItem(
        String kind,
        String eventId,
        long workSequence,
        String sourceType,
        String sourceId,
        Long sourceSequence,
        String eventType,
        String content,
        Map<String, Object> payload,
        Instant createdAt,
        String resumeToken
) {
    public UnifiedWorkStreamItem {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        content = content == null ? "" : content;
    }
}
