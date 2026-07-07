package com.agent.platform.rag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RagRunRecord(
        String ragRunId,
        String query,
        String retrievalMode,
        boolean enoughEvidence,
        int requestedTopK,
        int effectiveTopK,
        double minSimilarity,
        int retrievedDocuments,
        long durationMs,
        List<RagHitSnapshot> hits,
        Instant createdAt
) {

    public RagRunRecord {
        ragRunId = ragRunId == null || ragRunId.isBlank() ? UUID.randomUUID().toString() : ragRunId;
        hits = hits == null ? List.of() : List.copyOf(hits);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static RagRunRecord from(RagResult result) {
        List<RagHitSnapshot> hits = result.documents().stream()
                .map(RagHitSnapshot::from)
                .toList();
        return new RagRunRecord(
                UUID.randomUUID().toString(),
                result.query(),
                result.retrievalMode(),
                result.enoughEvidence(),
                result.requestedTopK(),
                result.effectiveTopK(),
                result.minSimilarity(),
                result.documents().size(),
                result.durationMs(),
                hits,
                Instant.now()
        );
    }
}
