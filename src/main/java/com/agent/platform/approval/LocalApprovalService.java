package com.agent.platform.approval;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LocalApprovalService implements ApprovalService {

    private final ApprovalStore approvalStore;

    public LocalApprovalService(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        ApprovalRecord requested = new ApprovalRecord(
                request.approvalId(),
                request.conversationId(),
                request.toolCallRequest(),
                request.reason(),
                ApprovalStatus.REQUESTED,
                "",
                "",
                request.createdAt() == null ? Instant.now() : request.createdAt(),
                null
        );
        approvalStore.save(requested);

        boolean approved = request.toolCallRequest() != null
                && "ticket_priority_update".equals(request.toolCallRequest().toolName());
        String reason = approved
                ? "local approval policy passed for controlled priority update"
                : "local approval policy rejected this operation";
        return decide(request.approvalId(), approved, "local-reviewer", reason);
    }

    @Override
    public ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason) {
        ApprovalRecord current = approvalStore.find(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + approvalId));
        Instant decidedAt = Instant.now();
        ApprovalRecord decided = new ApprovalRecord(
                current.approvalId(),
                current.conversationId(),
                current.toolCallRequest(),
                current.reason(),
                approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
                reviewer == null || reviewer.isBlank() ? "manual-reviewer" : reviewer,
                reason == null ? "" : reason,
                current.createdAt(),
                decidedAt
        );
        approvalStore.save(decided);
        return new ApprovalDecision(decided.approvalId(), approved, decided.reviewer(), decided.decisionReason(), decidedAt);
    }
}
