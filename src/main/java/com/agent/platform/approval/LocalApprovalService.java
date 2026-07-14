package com.agent.platform.approval;

import com.agent.platform.config.AgentProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class LocalApprovalService implements ApprovalService {

    private final ApprovalStore approvalStore;
    private final AgentProperties properties;

    public LocalApprovalService(ApprovalStore approvalStore, AgentProperties properties) {
        this.approvalStore = approvalStore;
        this.properties = properties;
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        Instant createdAt = request.createdAt() == null ? Instant.now() : request.createdAt();
        ApprovalRecord requested = new ApprovalRecord(
                request.approvalId(),
                request.runId(),
                request.conversationId(),
                request.toolCallRequest(),
                request.reason(),
                ApprovalStatus.REQUESTED,
                "",
                "",
                createdAt,
                createdAt.plusSeconds(properties.getApprovalTtlSeconds()),
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
        ApprovalRecord current = find(approvalId)
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
                current.expiresAt(),
                decidedAt
        );
        if (!approvalStore.transition(current.approvalId(), ApprovalStatus.REQUESTED, decided)) {
            ApprovalRecord winner = approvalStore.find(current.approvalId())
                    .orElseThrow(() -> new IllegalStateException(
                            "approval disappeared during decision: " + current.approvalId()
                    ));
            if (winner.status() == targetStatus) {
                return toDecision(winner);
            }
            throw new IllegalArgumentException(
                    "approval already decided as " + winner.status() + ": " + current.approvalId()
            );
        }
        return toDecision(decided);
    }

    private ApprovalDecision toDecision(ApprovalRecord record) {
        return new ApprovalDecision(
                record.approvalId(),
                record.status(),
                record.reviewer(),
                record.decisionReason(),
                record.decidedAt()
        );
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        return approvalStore.find(approvalId).map(this::expireIfNecessary);
    }

    private ApprovalRecord expireIfNecessary(ApprovalRecord current) {
        if (current.status() != ApprovalStatus.REQUESTED || Instant.now().isBefore(current.expiresAt())) {
            return current;
        }
        ApprovalRecord expired = new ApprovalRecord(
                current.approvalId(), current.runId(), current.conversationId(), current.toolCallRequest(),
                current.reason(), ApprovalStatus.EXPIRED, "system", "approval expired",
                current.createdAt(), current.expiresAt(), Instant.now()
        );
        if (approvalStore.transition(current.approvalId(), ApprovalStatus.REQUESTED, expired)) {
            return expired;
        }
        return approvalStore.find(current.approvalId()).orElse(current);
    }
}
