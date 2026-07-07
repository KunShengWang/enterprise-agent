package com.agent.platform.memory;

import java.util.List;

public record ConversationMemory(
        String conversationId,
        String userId,
        List<MemoryMessage> messages,
        String summary,
        List<LongTermMemory> longTermMemories,
        UserProfile userProfile,
        List<MemorySearchResult> recalledMemories
) {

    public ConversationMemory {
        messages = messages == null ? List.of() : List.copyOf(messages);
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        userProfile = userProfile == null ? UserProfile.empty(userId) : userProfile;
        recalledMemories = recalledMemories == null ? List.of() : List.copyOf(recalledMemories);
    }

    public ConversationMemory(String conversationId, List<MemoryMessage> messages, String summary) {
        this(conversationId, "", messages, summary, List.of(), UserProfile.empty(""), List.of());
    }

    public static ConversationMemory empty(String conversationId) {
        return new ConversationMemory(conversationId, "", List.of(), "", List.of(), UserProfile.empty(""), List.of());
    }

    public static ConversationMemory empty(String conversationId, String userId) {
        return new ConversationMemory(conversationId, userId, List.of(), "", List.of(), UserProfile.empty(userId), List.of());
    }
}
