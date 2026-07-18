package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceRecord(
        String evidenceId,
        String incidentId,
        String taskId,
        String childRunId,
        EvidenceClass evidenceClass,
        EvidenceSubtype evidenceSubtype,
        String sourceSystem,
        String sourceReference,
        Map<String, Object> queryParameters,
        Instant observedAt,
        Map<String, Object> facts,
        String payloadHash,
        EvidenceStatus status,
        String supersedesEvidenceId,
        String idempotencyKey,
        Instant createdAt
) {
    public EvidenceRecord {
        queryParameters = immutableMap(queryParameters);
        facts = immutableMap(facts);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
