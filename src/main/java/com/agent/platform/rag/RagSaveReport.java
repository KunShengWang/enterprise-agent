package com.agent.platform.rag;

public record RagSaveReport(
        int deletedChunks,
        int savedChunks
) {
}
