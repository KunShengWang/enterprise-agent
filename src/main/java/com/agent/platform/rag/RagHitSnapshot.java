package com.agent.platform.rag;

import java.util.Map;

public record RagHitSnapshot(
        int rank,
        String documentId,
        String source,
        Object chunkIndex,
        double score,
        Map<String, Object> metadata
) {

    public RagHitSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RagHitSnapshot from(RetrievedDocument document) {
        Map<String, Object> metadata = document.metadata();
        Object rankValue = metadata.getOrDefault("rank", 0);
        int rank = rankValue instanceof Number number ? number.intValue() : 0;
        return new RagHitSnapshot(
                rank,
                document.documentId(),
                String.valueOf(metadata.getOrDefault("source", document.title())),
                metadata.getOrDefault("chunkIndex", "unknown"),
                document.score(),
                metadata
        );
    }
}
