package com.agent.platform.ordercare.incident.recovery.model;

import java.time.Instant;
import java.util.Map;

public record RecoveryPlanEventRecord(
        String eventId,
        String planId,
        String incidentId,
        long sequence,
        String eventType,
        Map<String, Object> payload,
        Instant createdAt
) {
    public RecoveryPlanEventRecord {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
