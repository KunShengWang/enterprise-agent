package com.agent.platform.workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowResumeResult(
        String traceId,
        boolean resumable,
        WorkflowNode resumeFrom,
        List<WorkflowNode> skippedNodes,
        List<WorkflowNode> remainingNodes,
        String reason,
        Instant createdAt
) {

    public WorkflowResumeResult {
        skippedNodes = skippedNodes == null ? List.of() : List.copyOf(skippedNodes);
        remainingNodes = remainingNodes == null ? List.of() : List.copyOf(remainingNodes);
    }
}
