package com.agent.platform.workbench.eval;

import java.time.Instant;
import java.util.List;

public record WorkbenchRoutingEvalReport(
        String suiteVersion,
        Instant generatedAt,
        String modelName,
        int totalCases,
        int passedCases,
        int commandCases,
        int commandCorrect,
        int routeCases,
        int routeTargetCorrect,
        int routeDispositionCorrect,
        int ambiguousOrAdversarialCases,
        int dangerousMisrouteCount,
        int dangerousCommandMisclassificationCount,
        int wrongFocusCount,
        int identifierSourceViolationCount,
        int hiddenTargetSelectionCount,
        long promptTokens,
        long completionTokens,
        long totalLatencyMs,
        List<WorkbenchRoutingEvalCaseResult> results
) {
    public double passRate() {
        return ratio(passedCases, totalCases);
    }

    public double commandAccuracy() {
        return ratio(commandCorrect, commandCases);
    }

    public double routeTargetAccuracy() {
        return ratio(routeTargetCorrect, routeCases);
    }

    public double routeDispositionAccuracy() {
        return ratio(routeDispositionCorrect, routeCases);
    }

    public double wrongFocusRate() {
        return ratio(wrongFocusCount, commandCases);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
