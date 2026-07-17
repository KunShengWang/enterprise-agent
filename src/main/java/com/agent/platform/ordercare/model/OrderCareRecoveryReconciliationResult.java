package com.agent.platform.ordercare.model;

public record OrderCareRecoveryReconciliationResult(
        String status,
        int attempts,
        boolean responseLost,
        boolean executeReissuedWithSameId,
        OrderCareRecoveryAction action,
        OrderCareConvergenceResult convergence
) {
}
