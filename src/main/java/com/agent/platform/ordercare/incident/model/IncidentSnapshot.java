package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.List;

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
        String scopeHash
) {
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
