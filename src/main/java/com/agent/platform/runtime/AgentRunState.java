package com.agent.platform.runtime;

public enum AgentRunState {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    NEEDS_CLARIFICATION,
    BLOCKED,
    FAILED,
    REJECTED,
    MANUAL_REVIEW
}
