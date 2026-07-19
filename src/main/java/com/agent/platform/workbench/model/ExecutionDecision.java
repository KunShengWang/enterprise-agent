package com.agent.platform.workbench.model;

import java.util.List;
import java.util.Map;

public record ExecutionDecision(
        String targetId,
        double modelConfidence,
        String reason,
        Map<String, Object> extractedInputs,
        List<String> missingInputs,
        String userFacingSummary
) {
    public ExecutionDecision {
        targetId = targetId == null ? "" : targetId.trim();
        modelConfidence = Math.max(0, Math.min(1, modelConfidence));
        reason = reason == null ? "" : reason.trim();
        extractedInputs = extractedInputs == null ? Map.of() : Map.copyOf(extractedInputs);
        missingInputs = missingInputs == null ? List.of() : missingInputs.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        userFacingSummary = userFacingSummary == null ? "" : userFacingSummary.trim();
    }
}

