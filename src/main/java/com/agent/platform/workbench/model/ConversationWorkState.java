package com.agent.platform.workbench.model;

import java.time.Instant;

public record ConversationWorkState(
        String conversationId,
        String tenantId,
        String ownerPrincipalId,
        String focusedWorkItemId,
        long version,
        Instant updatedAt
) {
}
