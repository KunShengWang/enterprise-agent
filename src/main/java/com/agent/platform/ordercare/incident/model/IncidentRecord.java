package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record IncidentRecord(
        String incidentId,
        String commanderRunId,
        String reviewerRunId,
        String conversationId,
        String scenarioId,
        IncidentStatus status,
        IncidentSnapshot snapshot,
        Map<String, Object> delegationPlan,
        Map<String, Object> assessment,
        int clarificationCount,
        int maxClarifications,
        long nextEventSequence,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public IncidentRecord {
        delegationPlan = immutableMap(delegationPlan);
        assessment = immutableMap(assessment);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
