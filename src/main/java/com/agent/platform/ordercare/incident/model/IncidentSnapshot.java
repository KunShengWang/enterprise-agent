package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IncidentSnapshot(
        String snapshotId,
        String incidentId,
        String alertBatchId,
        String alertType,
        String tenantScope,
        IncidentOrderScope orderScope,
        IncidentBusinessScope businessScope,
        IncidentTimeWindow timeWindow,
        Instant detectedAt,
        Instant investigationStartedAt,
        Instant deadlineAt,
        String scopeHash,
        String scopeSnapshotId,
        String candidateFingerprint,
        Map<String, Object> scopeProvenance
) {
    public IncidentSnapshot {
        scopeSnapshotId = scopeSnapshotId == null ? "" : scopeSnapshotId.trim();
        candidateFingerprint = candidateFingerprint == null ? "" : candidateFingerprint.trim();
        scopeProvenance = scopeProvenance == null ? Map.of() : Map.copyOf(scopeProvenance);
    }

    public IncidentSnapshot(String snapshotId,
                            String incidentId,
                            String alertBatchId,
                            String alertType,
                            String tenantScope,
                            IncidentOrderScope orderScope,
                            IncidentBusinessScope businessScope,
                            IncidentTimeWindow timeWindow,
                            Instant detectedAt,
                            Instant investigationStartedAt,
                            Instant deadlineAt,
                            String scopeHash) {
        this(snapshotId, incidentId, alertBatchId, alertType, tenantScope, orderScope,
                businessScope, timeWindow, detectedAt, investigationStartedAt, deadlineAt,
                scopeHash, "", "", Map.of());
    }
    public record IncidentOrderScope(List<String> requestIds) {
        public IncidentOrderScope {
            requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        }
    }

    public record IncidentBusinessScope(List<String> queueNames) {
        public IncidentBusinessScope {
            queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
        }
    }

    public record IncidentTimeWindow(Instant from, Instant to) {
    }
}
