package com.agent.platform.runtime;

/**
 * Coarse persisted phase of the model-driven runtime. It is not a predefined workflow plan.
 */
public enum AgentRunPhase {
    START,
    WAITING_APPROVAL,
    EXECUTING_TOOL,
    FINISHED,
    BLOCKED,
    FAILED
}
