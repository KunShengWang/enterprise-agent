package com.agent.platform.ordercare.model;

import java.util.List;

/** FlowOrder Proposal v1 的强类型只读视图。 */
public record OrderCareRecoveryProposal(
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
) {

    public OrderCareRecoveryProposal {
        effects = effects == null ? List.of() : List.copyOf(effects);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
