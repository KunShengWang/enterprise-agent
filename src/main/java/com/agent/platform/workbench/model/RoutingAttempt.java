package com.agent.platform.workbench.model;

public record RoutingAttempt(
        String decisionId,
        String workItemId,
        String routingRequestId,
        int attemptNo,
        String traceId
) {
}

