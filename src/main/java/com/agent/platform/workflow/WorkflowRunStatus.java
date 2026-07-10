package com.agent.platform.workflow;

public enum WorkflowRunStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    BLOCKED,
    FAILED,
    REJECTED,
    MANUAL_REVIEW,
    INTERRUPTED,
    RESUMABLE
}
