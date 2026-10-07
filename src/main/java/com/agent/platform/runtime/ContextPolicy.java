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

    /** Tier A is never evicted. Only old, unmarked conversational prose is Tier C. */
    boolean disposableHistory(AgentMessage message, long recentFrom, String activeRun) {
        return (message.type() == AgentMessageType.USER || message.type() == AgentMessageType.ASSISTANT_TEXT)
                && message.sequence() < recentFrom && !java.util.Objects.equals(message.runId(), activeRun)
                && message.metadata().isEmpty();
    }

    /** Tier B projections retain identity, decision facts, provenance and governance verbatim. */
    boolean protectedResultField(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        return key.endsWith("id") || key.endsWith("ids") || key.contains("ref") || key.contains("digest")
                || key.contains("version") || key.contains("evidence") || key.contains("approval")
                || key.contains("policy") || key.contains("authorization") || key.contains("constraint")
                || java.util.Set.of("record", "recordstable", "offers", "evaluations", "recommendation", "facts", "source",
                        "specifications", "receipt", "receipts").contains(key);
    }
}
