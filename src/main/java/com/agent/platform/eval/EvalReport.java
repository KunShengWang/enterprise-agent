package com.agent.platform.eval;

public record EvalReport(
        int totalCases,
        int passedCases,
        double averageScore
) {
}
