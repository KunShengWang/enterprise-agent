package com.agent.platform.workbench.application;

public record RouterFailureObservation(
        String modelName,
        String promptDigest,
        String rawOutputDigest,
        long promptTokens,
        long completionTokens,
        long latencyMs
) {
    public static RouterFailureObservation empty() {
        return new RouterFailureObservation("", "", "", 0, 0, 0);
    }
}
