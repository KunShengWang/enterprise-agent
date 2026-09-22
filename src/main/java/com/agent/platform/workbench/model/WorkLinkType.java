package com.agent.platform.workbench.model;

public enum WorkLinkType {
    RUN,
    /** Read compatibility only; cannot dispatch or project new domain events. */
    INCIDENT,
    /** Read compatibility only; cannot dispatch or project new domain events. */
    RECOVERY_PLAN,
    APPROVAL
}
