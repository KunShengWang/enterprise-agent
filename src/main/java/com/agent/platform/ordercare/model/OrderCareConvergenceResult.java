package com.agent.platform.ordercare.model;

public record OrderCareConvergenceResult(
        String proposalId,
        String status,
        int attempts,
        String proposalStatus,
        String actionStatus,
        String caseOutcome,
        boolean deductReleased,
        boolean inventoryInvariantOk,
        boolean relatedDeadLettersTerminal
) {
}
