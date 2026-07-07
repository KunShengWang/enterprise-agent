package com.agent.platform.rag;

public record RagVectorIndexReport(
        String mode,
        String indexName,
        String indexType,
        String sql,
        long durationMs
) {
}
