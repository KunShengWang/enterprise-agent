package com.agent.platform.eval;

import java.time.Instant;
import java.util.List;

public record EvalReport(
        String runId,
        Instant createdAt,
        int totalCases,
        int passedCases,
        double passRate,
        double averageScore,
        double keywordHitRate,
        double toolCallSuccessRate,
        double ragUsageAccuracy,
        double groundednessRate,
        List<EvalCaseResult> results
) {

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
