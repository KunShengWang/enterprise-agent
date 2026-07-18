package com.agent.platform.agent;

public enum AgentRunStatus {
    RUNNING,
    PAUSED,
    WAITING_APPROVAL,
    WAITING_INPUT,
    COMPLETED,
    NEEDS_CLARIFICATION,
    BLOCKED,
    FAILED,
    REJECTED,
    MANUAL_REVIEW
}
