package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;

import java.util.List;

public record IncidentTaskExecution(
        AgentTaskRecord task,
        List<EvidenceRecord> evidence,
        List<EvidenceGap> gaps,
        boolean successful
) {
    public IncidentTaskExecution {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
