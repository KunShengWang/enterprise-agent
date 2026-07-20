package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.model.DispatchAttempt;

import java.time.Instant;

public record DispatchClaim(DispatchAttempt attempt, DispatchRequest request,
                            String leaseOwner, long fencingToken, Instant leaseUntil) {
    public DispatchClaim(DispatchAttempt attempt, DispatchRequest request) {
        this(attempt, request, "", 0, null);
    }
}
