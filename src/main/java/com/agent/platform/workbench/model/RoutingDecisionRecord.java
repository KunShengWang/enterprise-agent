package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Map;

public record RoutingDecisionRecord(
        String decisionId,
        String workItemId,
        String routingRequestId,
        int attemptNo,
        DecisionStatus decisionStatus,
        String modelName,
        String targetCatalogVersion,
        String promptDigest,
        String rawOutputDigest,
        Map<String, Object> decision,
        Map<String, Object> validation,
        long promptTokens,
        long completionTokens,
        long latencyMs,
        String failureCode,
        String failureReason,
        String traceId,
        Instant createdAt,
        Instant completedAt
) {
    public RoutingDecisionRecord {
        decision = decision == null ? Map.of() : Map.copyOf(decision);
        validation = validation == null ? Map.of() : Map.copyOf(validation);
    }
}

