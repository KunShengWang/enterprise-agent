package com.agent.platform.ordercare.incident.recovery.model;

public record RecoveryPlanStartRequest(String requestKey, String objective, String budgetOwnerWorkItemId) {
    public RecoveryPlanStartRequest(String requestKey, String objective) {
        this(requestKey, objective, "");
    }

    public RecoveryPlanStartRequest {
        budgetOwnerWorkItemId = budgetOwnerWorkItemId == null ? "" : budgetOwnerWorkItemId.trim();
    }
}
