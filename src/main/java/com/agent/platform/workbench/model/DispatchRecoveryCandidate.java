package com.agent.platform.workbench.model;

import com.agent.platform.workbench.security.AuthenticatedPrincipal;

public record DispatchRecoveryCandidate(
        AgentWorkItem workItem,
        AuthenticatedPrincipal principal
) {
}
