package com.agent.platform.workbench.model;

import java.time.Instant;

public record WorkCommandExecution(
        String commandRequestId,
        String inputId,
        String workItemId,
        String tenantId,
        String ownerPrincipalId,
        WorkCommandType commandType,
        long admittedWorkVersion,
        WorkCommandExecutionStatus status,
        String leaseOwner,
        Instant leaseUntil,
        long claimToken,
        String resultCode,
        boolean underlyingExecutionChanged,
        String underlyingRunId,
        String message,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
