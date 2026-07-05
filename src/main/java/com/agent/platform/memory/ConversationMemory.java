package com.agent.platform.memory;

import java.util.List;

public record ConversationMemory(
        String conversationId,
        List<MemoryMessage> messages,
        String summary
) {

    public ConversationMemory {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ConversationMemory empty(String conversationId) {
        return new ConversationMemory(conversationId, List.of(), "");
    }
}
