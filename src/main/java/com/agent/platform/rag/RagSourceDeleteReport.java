package com.agent.platform.rag;

public record RagSourceDeleteReport(
        String mode,
        String source,
        int deletedChunks
) {
}
