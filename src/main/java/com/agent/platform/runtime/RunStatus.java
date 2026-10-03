package com.agent.platform.runtime;

/** Compatibility mapping leaves persisted approval and workbench states intact. */
public enum RunStatus {
    RUNNING, COMPLETED, PAUSED, FAILED, CANCELLED;

    public static RunStatus from(AgentRunState state, AgentStopReason reason) {
        if (reason == AgentStopReason.CANCELLED) return CANCELLED;
        return switch (state) {
            case CREATED, RUNNING, PAUSE_REQUESTED -> RUNNING;
            case WAITING_APPROVAL, WAITING_INPUT, PAUSED, NEEDS_CLARIFICATION -> PAUSED;
            case COMPLETED -> COMPLETED;
            default -> FAILED;
        };
    }
}
