package com.agent.platform.workbench.model;

import java.time.Instant;

public record DispatchAttempt(
        String attemptId,
        String workItemId,
        String dispatchRequestId,
        int attemptNo,
        boolean reconciliation,
        String targetId,
        DispatchAttemptStatus status,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {
}
