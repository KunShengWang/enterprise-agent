package com.agent.platform.ordercare.incident.recovery.model;

public enum RecoveryPlanItemStatus {
    PREVIEWING,
    WAITING_APPROVAL,
    EXECUTING,
    RESOLVED,
    INELIGIBLE,
    REJECTED,
    MANUAL_REVIEW,
    FAILED;

    public boolean terminal() {
        return this == RESOLVED
                || this == INELIGIBLE
                || this == REJECTED
                || this == MANUAL_REVIEW
                || this == FAILED;
    }
}
