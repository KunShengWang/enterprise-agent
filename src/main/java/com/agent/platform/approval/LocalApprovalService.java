package com.agent.platform.approval;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

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
                request.runId(),
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
        return new ApprovalDecision(
                requested.approvalId(),
                ApprovalStatus.REQUESTED,
                "",
                "waiting for human decision",
                null
        );
    }

    @Override
    public ApprovalDecision decide(String approvalId, boolean approved, String reviewer, String reason) {
        ApprovalRecord current = approvalStore.find(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + approvalId));
        ApprovalStatus targetStatus = approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        if (current.status() != ApprovalStatus.REQUESTED) {
            if (current.status() == targetStatus) {
                return new ApprovalDecision(
                        current.approvalId(),
                        current.status(),
                        current.reviewer(),
                        current.decisionReason(),
                        current.decidedAt()
                );
            }
            throw new IllegalArgumentException(
                    "approval already decided as " + current.status() + ": " + approvalId
            );
        }
        Instant decidedAt = Instant.now();
        ApprovalRecord decided = new ApprovalRecord(
                current.approvalId(),
                current.runId(),
                current.conversationId(),
                current.toolCallRequest(),
                current.reason(),
                targetStatus,
                reviewer == null || reviewer.isBlank() ? "manual-reviewer" : reviewer,
                reason == null ? "" : reason,
                current.createdAt(),
                decidedAt
        );
        approvalStore.save(decided);
        return new ApprovalDecision(
                decided.approvalId(),
                decided.status(),
                decided.reviewer(),
                decided.decisionReason(),
                decidedAt
        );
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        return approvalStore.find(approvalId);
    }
}
