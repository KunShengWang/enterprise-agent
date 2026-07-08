package com.agent.platform.multiagent;

import java.util.List;

public record MultiAgentReviewResult(
        boolean approved,
        double confidence,
        boolean conflictDetected,
        String conflictReason,
        List<String> evidence,
        String finalAnswer
) {

    public MultiAgentReviewResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
