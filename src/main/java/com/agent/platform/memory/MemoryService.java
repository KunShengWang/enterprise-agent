package com.agent.platform.memory;

public interface MemoryService {

    ConversationMemory load(String conversationId);

    void append(String conversationId, MemoryMessage message);
}
