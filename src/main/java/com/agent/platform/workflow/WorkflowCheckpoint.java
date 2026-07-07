package com.agent.platform.workflow;

import java.time.Instant;

public record WorkflowCheckpoint(
        WorkflowNode node,
        String status,
        String summary,
        boolean retryable,
        boolean resumable,
        Instant createdAt
) {
}
