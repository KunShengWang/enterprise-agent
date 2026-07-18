package com.agent.platform.ordercare.incident.model;

public enum AgentTaskStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    WAITING_CLARIFICATION,
    RETRY_PENDING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }
}
