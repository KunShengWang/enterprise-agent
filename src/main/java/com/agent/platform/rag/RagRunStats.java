package com.agent.platform.rag;

import java.util.Map;

public record RagRunStats(
        int totalRuns,
        int hitRuns,
        double hitRate,
        double averageDurationMs,
        double averageRetrievedDocuments,
        Map<String, Long> runsByMode
) {

    public RagRunStats {
        runsByMode = runsByMode == null ? Map.of() : Map.copyOf(runsByMode);
    }
}
