package com.agent.platform.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalService {

    ApprovalDecision requestApproval(ApprovalRequest request);

    ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason);

    /**
     * 根据 approvalId 寻找审批记录，顺便检查审批是否已经过期，并通过并发安全的方式把状态从 REQUESTED 更新为 EXPIRED。
     */
    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> recent(int limit);
}
