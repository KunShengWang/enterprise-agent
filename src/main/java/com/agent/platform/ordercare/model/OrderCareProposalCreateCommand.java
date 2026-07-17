package com.agent.platform.ordercare.model;

public record OrderCareProposalCreateCommand(
        String proposalId,
        String identifierType,
        String identifierValue,
        String actionType,
        String suggestedReason
) {
}
