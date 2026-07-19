package com.agent.platform.workbench.model;

public enum WorkExecutionState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_INPUT,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN
}
