package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IncidentInvestigationRequest(
        String alertBatchId,
        String alertType,
        Instant detectedAt,
        String symptom,
        List<String> candidateRequestIds,
        List<String> queueNames,
        String budgetOwnerWorkItemId,
        String scopeSnapshotId,
        String candidateFingerprint,
        Map<String, Object> scopeProvenance
) {
    public IncidentInvestigationRequest {
        alertBatchId = alertBatchId == null ? "" : alertBatchId.trim();
        alertType = alertType == null ? "" : alertType.trim();
        detectedAt = detectedAt == null ? Instant.now() : detectedAt;
        symptom = symptom == null ? "" : symptom.trim();
        candidateRequestIds = candidateRequestIds == null ? List.of() : List.copyOf(candidateRequestIds);
        queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
        budgetOwnerWorkItemId = budgetOwnerWorkItemId == null ? "" : budgetOwnerWorkItemId.trim();
        scopeSnapshotId = scopeSnapshotId == null ? "" : scopeSnapshotId.trim();
        candidateFingerprint = candidateFingerprint == null ? "" : candidateFingerprint.trim();
        scopeProvenance = scopeProvenance == null ? Map.of() : Map.copyOf(scopeProvenance);
    }

    public IncidentInvestigationRequest(String alertBatchId,
                                        String alertType,
                                        Instant detectedAt,
                                        String symptom,
                                        List<String> candidateRequestIds,
                                        List<String> queueNames,
                                        String budgetOwnerWorkItemId) {
        this(alertBatchId, alertType, detectedAt, symptom, candidateRequestIds, queueNames,
                budgetOwnerWorkItemId, "", "", Map.of());
    }

    public IncidentInvestigationRequest(String alertBatchId,
                                        String alertType,
                                        Instant detectedAt,
                                        String symptom,
                                        List<String> candidateRequestIds,
                                        List<String> queueNames) {
        this(alertBatchId, alertType, detectedAt, symptom, candidateRequestIds, queueNames,
                "", "", "", Map.of());
    }
}
