package com.agent.platform.ordercare.incident.recovery.model;

import java.time.Instant;
import java.util.List;

public record IncidentRecoveryPlanRecord(
        String planId,
        String incidentId,
        String requestKey,
        String plannerRunId,
        String assessmentDigest,
        RecoveryPlanStatus status,
        RecoveryPlanOutcome outcome,
        RecoveryPlanDraft draft,
        List<IncidentRecoveryPlanItem> items,
        List<String> validationErrors,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public IncidentRecoveryPlanRecord {
        plannerRunId = plannerRunId == null ? "" : plannerRunId;
        items = items == null ? List.of() : List.copyOf(items);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
