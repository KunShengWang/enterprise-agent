package com.agent.platform.approval;

public interface ApprovalService {

    ApprovalDecision requestApproval(ApprovalRequest request);

    ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason);
}
