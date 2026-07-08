package com.agent.platform.rag;

public record RagCacheStats(
        boolean enabled,
        int size,
        long hits,
        long misses,
        double hitRate,
        long ttlSeconds,
        int maxEntries
) {
}
