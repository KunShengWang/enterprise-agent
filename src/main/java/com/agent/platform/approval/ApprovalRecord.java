package com.agent.platform.approval;

import com.agent.platform.tool.ToolCallRequest;

import java.time.Instant;

public record ApprovalRecord(
        String approvalId,
        String runId,
        String conversationId,
        ToolCallRequest toolCallRequest,
        String reason,
        ApprovalStatus status,
        String reviewer,
        String decisionReason,
        Instant createdAt,
        Instant decidedAt
) {

    public ApprovalRecord {
        runId = runId == null ? "" : runId;
    }

    public ApprovalRecord(String approvalId,
                          String conversationId,
                          ToolCallRequest toolCallRequest,
                          String reason,
                          ApprovalStatus status,
                          String reviewer,
                          String decisionReason,
                          Instant createdAt,
                          Instant decidedAt) {
        this(approvalId, "", conversationId, toolCallRequest, reason, status, reviewer, decisionReason, createdAt, decidedAt);
    }
}
