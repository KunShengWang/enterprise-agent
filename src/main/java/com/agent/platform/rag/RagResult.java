package com.agent.platform.rag;

import java.util.List;

public record RagResult(
        String query,
        List<RetrievedDocument> documents,
        boolean enoughEvidence,
        int requestedTopK,
        int effectiveTopK,
        double minSimilarity,
        long durationMs,
        String retrievalMode
) {

    public RagResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
        retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? "unknown" : retrievalMode;
    }

    public RagResult(String query, List<RetrievedDocument> documents, boolean enoughEvidence) {
        this(query, documents, enoughEvidence, 0, documents == null ? 0 : documents.size(), 0, 0, "unknown");
    }

    public static RagResult empty(String query) {
        return new RagResult(query, List.of(), false, 0, 0, 0, 0, "empty");
    }
}
