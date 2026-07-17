package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;

import java.time.Instant;

public record OrderCareProposalBinding(
        String proposalId,
        String actionRequestId,
        String caseKey,
        String previewToolExecutionId,
        String runId,
        OrderCareRecoveryProposal immutablePreview,
        Instant createdAt
) {

    public OrderCareProposalBinding {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
