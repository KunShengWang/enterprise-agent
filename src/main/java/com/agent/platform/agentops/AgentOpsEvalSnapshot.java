package com.agent.platform.agentops;

import com.agent.platform.eval.EvalQualityMetrics;
import com.agent.platform.eval.EvalReport;

import java.time.Instant;

public record AgentOpsEvalSnapshot(
        boolean available,
        String runId,
        Instant createdAt,
        int totalCases,
        double passRate,
        double averageScore,
        double keywordHitRate,
        double toolCallSuccessRate,
        double ragUsageAccuracy,
        double groundednessRate,
        double hallucinationRiskRate,
        double adversarialPassRate
) {

    public static AgentOpsEvalSnapshot empty() {
        return new AgentOpsEvalSnapshot(false, "", null, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static AgentOpsEvalSnapshot from(EvalReport report) {
        if (report == null) {
            return empty();
        }
        EvalQualityMetrics metrics = report.metrics();
        return new AgentOpsEvalSnapshot(
                true,
                report.runId(),
                report.createdAt(),
                report.totalCases(),
                report.passRate(),
                report.averageScore(),
                report.keywordHitRate(),
                report.toolCallSuccessRate(),
                report.ragUsageAccuracy(),
                report.groundednessRate(),
                metrics.hallucinationRiskRate(),
                metrics.adversarialPassRate()
        );
    }
}
