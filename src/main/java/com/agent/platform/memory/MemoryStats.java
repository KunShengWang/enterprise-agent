package com.agent.platform.memory;

public record MemoryStats(
        String mode,
        String conversationId,
        String userId,
        long messageCount,
        long summaryCount,
        long longTermMemoryCount,
        long profileItemCount
) {
}
