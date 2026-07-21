package com.agent.platform.ordercare.incident.scope.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IncidentScopeSnapshot(
        String snapshotId,
        String tenantId,
        String ownerPrincipalId,
        String conversationId,
        String workItemId,
        String sourceInputId,
        String discoveryRequestId,
        IncidentScopeCriteria criteria,
        String criteriaDigest,
        List<IncidentScopeCandidate> candidates,
        Map<String, String> sourceHealth,
        String candidateFingerprint,
        int candidateCount,
        boolean truncated,
        IncidentScopeSnapshotStatus status,
        long version,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        Instant expiresAt,
        Instant confirmedAt,
        String confirmedBy,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
    public IncidentScopeSnapshot {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        sourceHealth = sourceHealth == null ? Map.of() : Map.copyOf(sourceHealth);
        leaseOwner = leaseOwner == null ? "" : leaseOwner;
        confirmedBy = confirmedBy == null ? "" : confirmedBy;
        failureCode = failureCode == null ? "" : failureCode;
    }
}
