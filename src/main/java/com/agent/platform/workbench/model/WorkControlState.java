package com.agent.platform.workbench.model;

public enum WorkControlState {
    ROUTING,
    WAITING_INPUT,
    WAITING_CONFIRMATION,
    MANUAL_REVIEW,
    READY_TO_DISPATCH,
    DISPATCHING,
    DISPATCHED,
    PAUSE_REQUESTED,
    PAUSED,
    CANCEL_REQUESTED,
    ABANDONED,
    CLOSED
}
