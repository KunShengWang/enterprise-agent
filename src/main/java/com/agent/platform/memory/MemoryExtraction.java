package com.agent.platform.memory;

import java.util.List;

public record MemoryExtraction(
        List<LongTermMemoryDraft> longTermMemories,
        List<UserProfileItem> profileItems
) {

    public MemoryExtraction {
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        profileItems = profileItems == null ? List.of() : List.copyOf(profileItems);
    }

    public static MemoryExtraction empty() {
        return new MemoryExtraction(List.of(), List.of());
    }
}
