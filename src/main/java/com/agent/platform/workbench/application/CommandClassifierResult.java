package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.WorkCommandClassification;

public record CommandClassifierResult(
        WorkCommandClassification classification,
        ClassifierType classifierType,
        String modelName,
        String promptDigest,
        String rawOutputDigest,
        String rawOutput,
        long promptTokens,
        long completionTokens,
        long latencyMs,
        String traceId
) {
}

