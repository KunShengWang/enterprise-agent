package com.agent.platform.ordercare.incident.model;

public enum IncidentStatus {
    CREATED,
    PLANNING,
    INVESTIGATING,
    CHECKING_CONSISTENCY,
    REVIEWING,
    CLARIFYING,
    ASSESSED,
    PARTIAL,
    MANUAL_REVIEW,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == ASSESSED
                || this == PARTIAL
                || this == MANUAL_REVIEW
                || this == FAILED
                || this == CANCELLED;
    }
}
