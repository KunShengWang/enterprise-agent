package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

public record WorkCommandDecision(
        String commandDecisionId,
        String inputId,
        String conversationId,
        String tenantId,
        String ownerPrincipalId,
        String focusedWorkItemId,
        int attemptNo,
        ClassifierType classifierType,
        DecisionStatus decisionStatus,
        WorkCommandType commandType,
        String modelName,
        String promptDigest,
        String rawOutputDigest,
        Map<String, Object> decision,
        long promptTokens,
        long completionTokens,
        long latencyMs,
        double modelConfidence,
        String failureCode,
        String failureReason,
        String traceId,
        Instant createdAt,
        Instant completedAt
) {
    public WorkCommandDecision {
        decision = decision == null ? Map.of() : Map.copyOf(decision);
    }
}

