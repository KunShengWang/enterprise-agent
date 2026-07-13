package com.agent.platform.memory;

public interface MemoryExtractor {

    /**
     * 提炼用户画像和长期记忆
     */
    MemoryExtraction extract(String conversationId, String userId, MemoryMessage message);
}
