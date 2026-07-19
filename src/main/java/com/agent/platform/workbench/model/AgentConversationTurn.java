package com.agent.platform.workbench.model;

import java.time.Instant;

public record AgentConversationTurn(
        String inputId,
        String clientInputId,
        String conversationId,
        String tenantId,
        String ownerPrincipalId,
        String content,
        String contentDigest,
        String requestDigest,
        GoalOrigin goalOrigin,
        String commandDecisionId,
        String parentWorkItemId,
        WorkRelationType relationType,
        Instant createdAt
) {
}
