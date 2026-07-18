package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceCandidate(
        EvidenceClass evidenceClass,
        EvidenceSubtype evidenceSubtype,
        String sourceSystem,
        String sourceReference,
        Map<String, Object> queryParameters,
        Instant observedAt,
        Map<String, Object> facts,
        EvidenceStatus status,
        String supersedesEvidenceId,
        String idempotencyKey
) {
    public EvidenceCandidate {
        queryParameters = immutableMap(queryParameters);
        facts = immutableMap(facts);
        status = status == null ? EvidenceStatus.ACCEPTED : status;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
