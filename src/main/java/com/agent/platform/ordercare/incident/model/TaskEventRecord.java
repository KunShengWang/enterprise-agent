package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskEventRecord(
        String eventId,
        String incidentId,
        String taskId,
        String childRunId,
        long eventSequence,
        TaskEventType eventType,
        TaskEventCategory eventCategory,
        TaskEventActorType actorType,
        String actorId,
        String senderRole,
        String recipientRole,
        int messageDepth,
        String correlationId,
        String causationId,
        String idempotencyKey,
        Map<String, Object> payload,
        Instant createdAt
) {
    public TaskEventRecord {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
