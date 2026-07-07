package com.agent.platform.memory;

import java.time.Instant;
import java.util.List;

public interface MemoryService {

    default ConversationMemory load(String conversationId) {
        return load(conversationId, null, null);
    }

    default ConversationMemory load(String conversationId, String userId) {
        return load(conversationId, userId, null);
    }

    ConversationMemory load(String conversationId, String userId, String query);

    default void append(String conversationId, MemoryMessage message) {
        append(conversationId, null, message);
    }

    void append(String conversationId, String userId, MemoryMessage message);

    List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit);

    MemorySnapshot snapshot(String conversationId, String userId, String query, int limit);

    MemoryStats stats(String conversationId, String userId);

    UserProfile loadUserProfile(String userId);

    void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt);

    void clearConversation(String conversationId);

    void clearUserMemory(String userId);
}
