package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskResultSubmission(
        String incidentId,
        String taskId,
        String childRunId,
        long expectedVersion,
        String idempotencyKey,
        AgentTaskStatus targetStatus,
        Map<String, Object> outputSummary,
        List<EvidenceCandidate> evidence
) {
    public TaskResultSubmission {
        outputSummary = outputSummary == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(outputSummary));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
