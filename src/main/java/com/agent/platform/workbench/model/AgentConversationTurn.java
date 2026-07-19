package com.agent.platform.workbench.model;

import java.time.Instant;
import java.util.Set;

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
        Instant createdAt,
        WorkInputKind inputKind,
        WorkCommandType commandType,
        String targetWorkItemId,
        InputClassificationStatus classificationStatus,
        String classificationReason,
        Instant classifiedAt,
        Set<String> principalRoles,
        long version
) {
    public AgentConversationTurn {
        principalRoles = principalRoles == null ? Set.of() : Set.copyOf(principalRoles);
    }
}
