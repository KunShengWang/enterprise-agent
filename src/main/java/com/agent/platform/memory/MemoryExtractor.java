package com.agent.platform.memory;

public interface MemoryExtractor {

    MemoryExtraction extract(String conversationId, String userId, MemoryMessage message);
}
