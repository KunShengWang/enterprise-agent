package com.agent.platform.rag;

import java.util.List;

public record IngestionReport(
        String mode,
        String documentDir,
        int loadedDocuments,
        int chunks,
        int deletedChunks,
        int savedChunks,
        long embeddingDurationMs,
        long databaseDurationMs,
        long durationMs,
        List<String> sources,
        List<SourceIngestionReport> sourceReports
) {

    public IngestionReport {
        sources = sources == null ? List.of() : List.copyOf(sources);
        sourceReports = sourceReports == null ? List.of() : List.copyOf(sourceReports);
    }

    public IngestionReport(String mode,
                           String documentDir,
                           int loadedDocuments,
                           int chunks,
                           long durationMs,
                           List<String> sources) {
        this(mode, documentDir, loadedDocuments, chunks, 0, chunks, 0, 0, durationMs, sources, List.of());
    }
}
