package com.agent.platform.runtime;

/** Server-owned per-agent working-context policy; never accepted from request metadata. */
public record ContextPolicy(boolean enabled, long maxContextTokens, long reservedOutputTokens,
                            double compactionThreshold, int recentUserTurns) {
    public ContextPolicy {
        if (maxContextTokens < 1 || reservedOutputTokens < 0 || reservedOutputTokens >= maxContextTokens
                || compactionThreshold <= 0 || compactionThreshold > 1 || recentUserTurns < 1)
            throw new IllegalArgumentException("invalid context policy");
    }
    public static ContextPolicy disabled() { return new ContextPolicy(false, 32000, 2000, 0.75, 2); }
}
