package com.agent.platform.runtime;

import java.time.Instant;

/**
 * Agent 会话及其两个单调递增序号游标。
 */
public record AgentSession(
        String sessionId,
        String userId,
        long nextMessageSequence,
        long nextEventSequence,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
