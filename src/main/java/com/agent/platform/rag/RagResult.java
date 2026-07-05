package com.agent.platform.rag;

import java.util.List;

public record RagResult(
        String query,
        List<RetrievedDocument> documents,
        boolean enoughEvidence
) {

    public RagResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public static RagResult empty(String query) {
        return new RagResult(query, List.of(), false);
    }
}
