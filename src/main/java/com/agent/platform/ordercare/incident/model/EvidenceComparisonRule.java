package com.agent.platform.ordercare.incident.model;

import java.time.Duration;

public record EvidenceComparisonRule(
        String ruleId,
        EvidenceSubtype leftSubtype,
        String leftMetric,
        EvidenceSubtype rightSubtype,
        String rightMetric,
        BusinessKeyType businessKeyType,
        Duration maxObservedAtSkew,
        ComparisonOperator operator
) {
    public EvidenceComparisonRule {
        if (ruleId == null || ruleId.isBlank()
                || leftSubtype == null || rightSubtype == null
                || leftMetric == null || leftMetric.isBlank()
                || rightMetric == null || rightMetric.isBlank()
                || businessKeyType == null || operator == null) {
            throw new IllegalArgumentException("evidence comparison rule is incomplete");
        }
        maxObservedAtSkew = maxObservedAtSkew == null ? Duration.ofMinutes(2) : maxObservedAtSkew;
    }
}
