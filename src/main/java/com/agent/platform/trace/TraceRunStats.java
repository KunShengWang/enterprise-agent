package com.agent.platform.trace;

public record TraceRunStats(
        int totalRuns,
        int completedRuns,
        int failedRuns,
        int blockedRuns,
        double averageDurationMs,
        long estimatedPromptTokens,
        long estimatedCompletionTokens,
        double estimatedCost
) {
}
