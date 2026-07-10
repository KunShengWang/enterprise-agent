package com.agent.platform.workflow;

import com.agent.platform.agent.AgentRunStatus;

import java.time.Instant;
import java.util.List;

public record WorkflowResumeResult(
        String traceId,
        boolean resumable,
        WorkflowNode resumeFrom,
        List<WorkflowNode> skippedNodes,
        List<WorkflowNode> remainingNodes,
        AgentRunStatus runStatus,
        String answer,
        String approvalId,
        String reason,
        Instant createdAt
) {

    public WorkflowResumeResult {
        skippedNodes = skippedNodes == null ? List.of() : List.copyOf(skippedNodes);
        remainingNodes = remainingNodes == null ? List.of() : List.copyOf(remainingNodes);
        answer = answer == null ? "" : answer;
        approvalId = approvalId == null ? "" : approvalId;
    }

    public WorkflowResumeResult(String traceId,
                                boolean resumable,
                                WorkflowNode resumeFrom,
                                List<WorkflowNode> skippedNodes,
                                List<WorkflowNode> remainingNodes,
                                String reason,
                                Instant createdAt) {
        this(traceId, resumable, resumeFrom, skippedNodes, remainingNodes, null, "", "", reason, createdAt);
    }
}
