package com.agent.platform.memory;

public record LongTermMemoryDraft(
        DurableMemoryType type,
        String content,
        double confidence
) {

    public LongTermMemoryDraft {
        if (type == null) {
            throw new IllegalArgumentException("long-term memory type must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("long-term memory content must not be blank");
        }
        content = content.trim();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("long-term memory confidence must be finite and within [0,1]");
        }
    }
}
