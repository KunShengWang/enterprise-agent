package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.List;

public record IncidentInvestigationRequest(
        String alertBatchId,
        String alertType,
        Instant detectedAt,
        String symptom,
        List<String> candidateRequestIds,
        List<String> queueNames
) {
    public IncidentInvestigationRequest {
        alertBatchId = alertBatchId == null ? "" : alertBatchId.trim();
        alertType = alertType == null ? "" : alertType.trim();
        detectedAt = detectedAt == null ? Instant.now() : detectedAt;
        symptom = symptom == null ? "" : symptom.trim();
        candidateRequestIds = candidateRequestIds == null ? List.of() : List.copyOf(candidateRequestIds);
        queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
    }
}
