package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.model.ValidatedExecutionInput;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Instant;

public record DispatchRequest(
        String dispatchRequestId,
        String workItemId,
        String conversationId,
        String goalText,
        String targetId,
        AuthenticatedPrincipal principal,
        ValidatedExecutionInput validatedInput,
        Instant requestedAt
) {
    public DispatchRequest {
        if (dispatchRequestId == null || dispatchRequestId.isBlank()
                || workItemId == null || workItemId.isBlank()
                || targetId == null || targetId.isBlank()
                || principal == null || validatedInput == null || requestedAt == null) {
            throw new IllegalArgumentException("dispatch identity, target, principal and validated input are required");
        }
        dispatchRequestId = dispatchRequestId.trim();
        workItemId = workItemId.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
        goalText = goalText == null ? "" : goalText.trim();
        targetId = targetId.trim();
    }
}
