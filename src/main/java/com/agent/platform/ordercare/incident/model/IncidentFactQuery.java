package com.agent.platform.ordercare.incident.model;

import java.util.List;

public record IncidentFactQuery(
        String incidentId,
        String snapshotId,
        String scopeHash,
        List<String> requestIds,
        List<String> queueNames,
        Integer maxRecords
) {
    public IncidentFactQuery {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
    }
}
