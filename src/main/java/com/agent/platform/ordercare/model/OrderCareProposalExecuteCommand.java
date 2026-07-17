package com.agent.platform.ordercare.model;

public record OrderCareProposalExecuteCommand(
        String proposalId,
        Integer proposalVersion,
        String stateFingerprint,
        String effectsDigest,
        String warningsDigest,
        String previewDigest,
        String approvalId,
        String approvedBy,
        String approvalComment,
        String executionOwner
) {
}
