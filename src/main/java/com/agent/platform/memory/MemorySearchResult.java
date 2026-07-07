package com.agent.platform.memory;

import java.util.Map;

public record MemorySearchResult(
        String type,
        String id,
        String content,
        double score,
        Map<String, Object> metadata
) {

    public MemorySearchResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
