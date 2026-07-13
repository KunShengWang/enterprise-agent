package com.agent.platform.memory;

import java.time.Instant;
import java.util.List;

/**
 * Durable long-term memory boundary. Ordered conversation history belongs to AgentTimelineStore.
 */
public interface MemoryService {

    void rememberLongTerm(String conversationId, String userId, MemoryMessage message);

    List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit);

    UserProfile loadUserProfile(String userId);

    void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt);

    void clearConversation(String conversationId);

    void clearUserMemory(String userId);
}
