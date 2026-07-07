package com.agent.platform.memory;

public record LongTermMemoryDraft(
        String category,
        String content,
        double confidence
) {
}
