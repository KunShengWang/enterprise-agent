package com.agent.platform.approval;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LocalApprovalService implements ApprovalService {

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        boolean approved = request.toolCallRequest() != null
                && "ticket_priority_update".equals(request.toolCallRequest().toolName());
        String reason = approved
                ? "local approval policy passed for controlled priority update"
                : "local approval policy rejected this operation";
        return new ApprovalDecision(request.approvalId(), approved, "local-reviewer", reason, Instant.now());
    }
}
