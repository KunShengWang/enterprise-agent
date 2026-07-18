package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;

import java.util.List;

public record TaskResultCommitResult(
        AgentTaskRecord task,
        List<EvidenceRecord> evidence,
        TaskEventRecord event,
        boolean duplicate
) {
    public TaskResultCommitResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
