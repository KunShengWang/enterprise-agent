package com.agent.platform.ordercare.incident.model;

import java.util.List;

public record DelegationPlan(
        String schemaVersion,
        String incidentId,
        String planSummary,
        List<DelegatedTask> tasks
) {
    public DelegationPlan {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        incidentId = incidentId == null ? "" : incidentId.trim();
        planSummary = planSummary == null ? "" : planSummary.trim();
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public record DelegatedTask(
            String clientTaskKey,
            IncidentAgentRole role,
            String objective,
            int priority,
            List<String> dependencies,
            List<EvidenceSubtype> requiredEvidenceSubtypes
    ) {
        public DelegatedTask {
            clientTaskKey = clientTaskKey == null ? "" : clientTaskKey.trim();
            objective = objective == null ? "" : objective.trim();
            priority = Math.max(0, Math.min(100, priority));
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            requiredEvidenceSubtypes = requiredEvidenceSubtypes == null
                    ? List.of()
                    : List.copyOf(requiredEvidenceSubtypes);
        }
    }
}
