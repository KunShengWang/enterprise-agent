package com.agent.platform.ordercare.incident.recovery.model;

import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;

import java.time.Instant;
import java.util.List;

public record IncidentRecoveryPlanItem(
        String itemId,
        String clientItemKey,
        String identifierType,
        String identifierValue,
        String actionType,
        String suggestedReason,
        List<String> evidenceIds,
        List<String> conflictIds,
        RecoveryPlanItemStatus status,
        OrderCareRecoveryProposal proposal,
        String approvalId,
        String approvalStatus,
        String actionStatus,
        String caseOutcome,
        OrderCareConvergenceResult convergence,
        String lastError,
        String executionOwner,
        long fencingToken,
        Instant leaseUntil,
        Instant lastHeartbeatAt,
        int takeoverCount,
        Instant updatedAt
) {
    public IncidentRecoveryPlanItem {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        conflictIds = conflictIds == null ? List.of() : List.copyOf(conflictIds);
        approvalId = approvalId == null ? "" : approvalId;
        approvalStatus = approvalStatus == null ? "NOT_REQUESTED" : approvalStatus;
        actionStatus = actionStatus == null ? "NOT_STARTED" : actionStatus;
        caseOutcome = caseOutcome == null ? "NOT_CONVERGED" : caseOutcome;
        lastError = lastError == null ? "" : lastError;
        executionOwner = executionOwner == null ? "" : executionOwner;
        fencingToken = Math.max(0, fencingToken);
        takeoverCount = Math.max(0, takeoverCount);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public boolean leaseOwnedBy(String owner, long token, Instant now) {
        return !executionOwner.isBlank()
                && executionOwner.equals(owner)
                && fencingToken == token
                && leaseUntil != null
                && now != null
                && leaseUntil.isAfter(now);
    }
}
