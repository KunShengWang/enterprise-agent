package com.agent.platform.workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowRunRecord(
        String traceId,
        String conversationId,
        WorkflowRunStatus status,
        WorkflowExecutionPlan plan,
        List<WorkflowCheckpoint> checkpoints,
        String failureReason,
        Instant updatedAt
) {

    public WorkflowRunRecord {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }
}
