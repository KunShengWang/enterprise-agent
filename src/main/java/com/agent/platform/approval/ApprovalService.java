package com.agent.platform.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalService {

    ApprovalDecision requestApproval(ApprovalRequest request);

    ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason);

    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> recent(int limit);
}
