package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentTaskRecord(
        String taskId,
        String incidentId,
        String clientTaskKey,
        String taskType,
        String role,
        String objective,
        int priority,
        List<String> dependencies,
        List<EvidenceSubtype> requiredEvidenceSubtypes,
        Map<String, Object> inputPayload,
        Map<String, Object> outputSummary,
        AgentTaskStatus status,
        int attempt,
        int maxAttempts,
        String childRunId,
        String firstChildRunId,
        Instant deadlineAt,
        String claimedBy,
        Instant claimUntil,
        long fencingToken,
        Instant lastHeartbeatAt,
        String lastError,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentTaskRecord {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requiredEvidenceSubtypes = requiredEvidenceSubtypes == null
                ? List.of()
                : List.copyOf(requiredEvidenceSubtypes);
        inputPayload = immutableMap(inputPayload);
        outputSummary = immutableMap(outputSummary);
        fencingToken = Math.max(0, fencingToken);
    }

    public boolean leaseOwnedBy(String owner, long token, Instant now) {
        return owner != null && owner.equals(claimedBy) && token == fencingToken
                && claimUntil != null && now != null && claimUntil.isAfter(now);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
