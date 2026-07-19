package com.agent.platform.workbench.model;

import java.util.Map;

public record ValidatedExecutionInput(
        String targetId,
        Map<String, ValidatedIdentifier> identifiers,
        Map<String, Object> typedPayload,
        String inputDigest
) {
    public ValidatedExecutionInput {
        targetId = targetId == null ? "" : targetId.trim();
        identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
        typedPayload = typedPayload == null ? Map.of() : Map.copyOf(typedPayload);
        inputDigest = inputDigest == null ? "" : inputDigest.trim();
    }
}

