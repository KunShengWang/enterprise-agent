package com.agent.platform.memory;

import java.util.List;

public record MemorySnapshot(
        String conversationId,
        String userId,
        List<MemoryMessage> recentMessages,
        String summary,
        List<LongTermMemory> longTermMemories,
        UserProfile userProfile,
        List<MemorySearchResult> recalledMemories,
        MemoryStats stats
) {

    public MemorySnapshot {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        userProfile = userProfile == null ? UserProfile.empty(userId) : userProfile;
        recalledMemories = recalledMemories == null ? List.of() : List.copyOf(recalledMemories);
    }
}
