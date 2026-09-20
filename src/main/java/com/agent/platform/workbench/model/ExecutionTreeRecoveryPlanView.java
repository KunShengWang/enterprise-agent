package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.List;

/** Read-only compatibility projection of the existing recovery-plan JSON shape.
 * No approval, execution or lease behavior is exposed by these presentation records.
 */
public record ExecutionTreeRecoveryPlanView(
        String planId,
        String incidentId,
        String requestKey,
        String plannerRunId,
        String assessmentDigest,
        String status,
        String outcome,
        Draft draft,
        List<Item> items,
        List<String> validationErrors,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public record Draft(
            String schemaVersion,
            String summary,
            List<ProposalRequest> proposalRequests
    ) { }

    public record ProposalRequest(
            String clientItemKey,
            String identifierType,
            String identifierValue,
            String actionType,
            String suggestedReason,
            List<String> evidenceIds,
            List<String> conflictIds
    ) { }

    public record Item(
            String itemId,
            String clientItemKey,
            String identifierType,
            String identifierValue,
            String actionType,
            String suggestedReason,
            List<String> evidenceIds,
            List<String> conflictIds,
            String status,
            Proposal proposal,
            String approvalId,
            String approvalStatus,
            String actionStatus,
            String caseOutcome,
            Convergence convergence,
            String lastError,
            String executionOwner,
            long fencingToken,
            Instant leaseUntil,
            Instant lastHeartbeatAt,
            int takeoverCount,
            Instant updatedAt
    ) { }

    public record Proposal(
            String schemaVersion,
            String proposalId,
            Integer proposalVersion,
            String proposalStatus,
            String actionRequestId,
            String actionStatus,
            String caseOutcome,
            String caseKey,
            String identifierType,
            String identifierValue,
            String actionType,
            String targetType,
            String targetKey,
            String stateFingerprint,
            String effectsDigest,
            String warningsDigest,
            String previewDigest,
            Boolean canExecute,
            List<String> effects,
            List<String> warnings,
            String suggestedReason,
            String approvalId,
            String approvedBy,
            String approvalComment,
            String approvedAt,
            String expiresAt,
            String createdAt,
            String updatedAt
    ) { }

    public record Convergence(
            String proposalId,
            String status,
            int attempts,
            String proposalStatus,
            String actionStatus,
            String caseOutcome,
            boolean deductReleased,
            boolean inventoryInvariantOk,
            boolean relatedDeadLettersTerminal
    ) { }
}
