package com.agent.platform.rag;

public interface RagCacheOperations {

    RagCacheStats cacheStats();

    void clearCache();
}
