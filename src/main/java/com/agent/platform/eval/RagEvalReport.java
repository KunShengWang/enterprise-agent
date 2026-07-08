package com.agent.platform.eval;

import java.util.List;

public record RagEvalReport(
        int totalCases,
        int passedCases,
        double passRate,
        double averageScore,
        double sourceHitRate,
        double keywordHitRate,
        double recallAtK,
        double meanReciprocalRank,
        long totalDurationMs,
        List<RagEvalCaseResult> results
) {

    public RagEvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
