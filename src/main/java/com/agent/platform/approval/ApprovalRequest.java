package com.agent.platform.approval;

import com.agent.platform.tool.ToolCallRequest;

import java.time.Instant;

public record ApprovalRequest(
        String approvalId,
        String conversationId,
        ToolCallRequest toolCallRequest,
        String reason,
        Instant createdAt
) {
}
