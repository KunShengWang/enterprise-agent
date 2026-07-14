package com.agent.platform.approval;

import com.agent.platform.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LocalApprovalService implements ApprovalService {

    private final ApprovalStore approvalStore;
    private final AgentProperties properties;
    private final Clock clock;

    @Autowired
    public LocalApprovalService(ApprovalStore approvalStore, AgentProperties properties) {
        this(approvalStore, properties, Clock.systemUTC());
    }

    LocalApprovalService(ApprovalStore approvalStore, AgentProperties properties, Clock clock) {
        this.approvalStore = approvalStore;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        Instant createdAt = request.createdAt() == null ? clock.instant() : request.createdAt();
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
        Instant decidedAt = clock.instant();
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
        if (!approvalStore.decideIfRequestedAndNotExpired(current.approvalId(), decided, decidedAt)) {
            ApprovalRecord winner = find(current.approvalId())
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

    @Override
    public List<ApprovalRecord> recent(int limit) {
        return approvalStore.recent(limit).stream()
                .map(this::expireIfNecessary)
                .toList();
    }

    private ApprovalRecord expireIfNecessary(ApprovalRecord current) {
        Instant checkedAt = clock.instant();
        if (current.status() != ApprovalStatus.REQUESTED || checkedAt.isBefore(current.expiresAt())) {
            return current;
        }
        ApprovalRecord expired = new ApprovalRecord(
                current.approvalId(), current.runId(), current.conversationId(), current.toolCallRequest(),
                current.reason(), ApprovalStatus.EXPIRED, "system", "approval expired",
                current.createdAt(), current.expiresAt(), checkedAt
        );
        if (approvalStore.expireIfRequested(current.approvalId(), expired, checkedAt)) {
            return expired;
        }
        return approvalStore.find(current.approvalId()).orElse(current);
    }
}
