package com.agent.platform.rag;

import java.util.List;

public record RagCorpusStats(
        String mode,
        long totalChunks,
        List<SourceStats> sources
) {

    public RagCorpusStats {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record SourceStats(
            String source,
            long chunks
    ) {
    }
}
