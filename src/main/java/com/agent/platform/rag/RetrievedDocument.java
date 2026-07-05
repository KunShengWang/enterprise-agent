package com.agent.platform.rag;

import java.util.Map;

public record RetrievedDocument(
        String documentId,
        String title,
        String content,
        double score,
        Map<String, Object> metadata
) {

    public RetrievedDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
