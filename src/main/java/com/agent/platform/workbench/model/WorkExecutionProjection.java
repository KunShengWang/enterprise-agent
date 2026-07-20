package com.agent.platform.workbench.model;

import java.time.Instant;

public record WorkExecutionProjection(
        String sourceType,
        String sourceId,
        long sourceVersion,
        int sourceAttempt,
        String sourceStatus,
        String sourceOutcome,
        WorkControlState controlState,
        WorkExecutionState executionState,
        WorkOutcome outcome,
        Instant sourceUpdatedAt,
        Instant completedAt
) {
    public WorkExecutionProjection {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()
                || sourceVersion < 0 || sourceAttempt < 0 || sourceStatus == null || sourceStatus.isBlank()
                || controlState == null || executionState == null || outcome == null
                || sourceUpdatedAt == null) {
            throw new IllegalArgumentException("complete execution projection snapshot is required");
        }
        sourceOutcome = sourceOutcome == null ? "" : sourceOutcome;
    }
}
