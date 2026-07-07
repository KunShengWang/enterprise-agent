package com.agent.platform.rag;

import java.time.Instant;

public record RagRunReportFile(
        String reportId,
        String reportPath,
        int includedRuns,
        RagRunStats stats,
        Instant createdAt
) {
}
