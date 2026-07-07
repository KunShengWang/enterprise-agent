package com.agent.platform.rag;

public record EmbeddedChunk(
        DocumentChunk chunk,
        double[] embedding,
        String contentHash
) {
}
