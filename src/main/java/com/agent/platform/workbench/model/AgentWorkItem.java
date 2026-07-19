package com.agent.platform.workbench.model;

import java.time.Instant;

public record AgentWorkItem(
        String workItemId,
        String conversationId,
        String tenantId,
        String ownerPrincipalId,
        String originalGoal,
        String normalizedGoal,
        WorkControlState controlState,
        WorkExecutionState executionState,
        WorkOutcome outcome,
        String activeExecutionTarget,
        String activeRunId,
        String activeIncidentId,
        String activeRecoveryPlanId,
        String routeDecisionId,
        String sourceInputId,
        String parentWorkItemId,
        String routingRequestId,
        int routingAttemptCount,
        Instant routingLastAttemptAt,
        Instant routingNextRetryAt,
        String routingFailureCode,
        String dispatchRequestId,
        long nextEventSequence,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
