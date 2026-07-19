package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.ExecutionDecision;

public record RouterModelResult(
        ExecutionDecision decision,
        String modelName,
        String promptDigest,
        String rawOutputDigest,
        String rawOutput,
        long promptTokens,
        long completionTokens,
        long latencyMs
) {
}

