package com.agent.platform.eval;

public record EvalQualityMetrics(
        double keywordRecall,
        double toolPrecision,
        double toolRecall,
        double toolF1,
        double forbiddenViolationRate,
        double hallucinationRiskRate,
        int adversarialCases,
        int adversarialPassedCases,
        double adversarialPassRate
) {

    public static EvalQualityMetrics empty() {
        return new EvalQualityMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
