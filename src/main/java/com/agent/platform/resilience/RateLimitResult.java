package com.agent.platform.resilience;

public record RateLimitResult(
        boolean allowed,
        String key,
        int limit,
        int remaining,
        long resetEpochMillis
) {
}
