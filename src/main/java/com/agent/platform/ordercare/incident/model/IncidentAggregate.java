package com.agent.platform.ordercare.incident.model;

import java.util.List;

public record IncidentAggregate(
        IncidentRecord incident,
        List<AgentTaskRecord> tasks,
        List<EvidenceRecord> evidence,
        List<TaskEventRecord> events
) {
    public IncidentAggregate {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
