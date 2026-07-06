package com.agent.platform.approval;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class MockApprovalService implements ApprovalService {

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        boolean approved = request.toolCallRequest() != null
                && "ticket_priority_update".equals(request.toolCallRequest().toolName());
        String reason = approved
                ? "mock approval passed for controlled priority update"
                : "mock approval denied";
        return new ApprovalDecision(request.approvalId(), approved, "mock-reviewer", reason, Instant.now());
    }
}
