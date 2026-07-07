package com.agent.platform.memory;

import java.time.Instant;

public record LongTermMemory(
        String memoryId,
        String conversationId,
        String userId,
        String category,
        String content,
        double confidence,
        Instant createdAt,
        Instant updatedAt
) {
}
