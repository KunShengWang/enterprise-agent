package com.agent.platform.approval;

import java.time.Instant;

public record ApprovalDecision(
        String approvalId,
        boolean approved,
        String reviewer,
        String reason,
        Instant decidedAt
) {
}
