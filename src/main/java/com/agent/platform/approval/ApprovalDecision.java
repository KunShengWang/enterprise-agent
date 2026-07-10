package com.agent.platform.approval;

import java.time.Instant;

public record ApprovalDecision(
        String approvalId,
        ApprovalStatus status,
        String reviewer,
        String reason,
        Instant decidedAt
) {

    public boolean approved() {
        return status == ApprovalStatus.APPROVED;
    }

    public boolean pending() {
        return status == ApprovalStatus.REQUESTED;
    }
}
