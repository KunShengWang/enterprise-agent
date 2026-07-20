package com.agent.platform.workbench.model;

import java.time.Instant;

public record WorkProjectionClaim(
        WorkProjectionSource source,
        String leaseOwner,
        long fencingToken,
        Instant leaseUntil
) {
    public WorkProjectionClaim {
        if (source == null || leaseOwner == null || leaseOwner.isBlank() || fencingToken <= 0 || leaseUntil == null) {
            throw new IllegalArgumentException("projection source, lease owner, fencing token and lease are required");
        }
    }
}
