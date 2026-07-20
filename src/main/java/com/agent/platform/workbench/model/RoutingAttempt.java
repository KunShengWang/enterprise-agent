package com.agent.platform.workbench.model;

import java.time.Instant;

public record RoutingAttempt(
        String decisionId,
        String workItemId,
        String routingRequestId,
        int attemptNo,
        String traceId,
        String leaseOwner,
        long fencingToken,
        Instant leaseUntil
) {
    public RoutingAttempt(String decisionId, String workItemId, String routingRequestId,
                          int attemptNo, String traceId) {
        this(decisionId, workItemId, routingRequestId, attemptNo, traceId, "", 0, null);
    }
}
