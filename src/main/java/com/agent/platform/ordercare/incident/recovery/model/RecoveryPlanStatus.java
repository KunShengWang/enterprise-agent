package com.agent.platform.ordercare.incident.recovery.model;

public enum RecoveryPlanStatus {
    CREATED,
    PLANNING,
    PREVIEWING,
    WAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
