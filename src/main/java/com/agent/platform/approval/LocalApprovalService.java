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
        // 按 approvalId 找审批记录
        ApprovalRecord current = find(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval request not found: " + approvalId));
        // 计算目标状态
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
        // 并发安全落库（关键）
        if (!approvalStore.decideIfRequestedAndNotExpired(current.approvalId(), decided, decidedAt)) {
            ApprovalRecord winner = find(current.approvalId())
                    .orElseThrow(() -> new IllegalStateException(
                            "approval disappeared during decision: " + current.approvalId()
                    ));
            // 幂等，状态已经是目标状态 → 直接返回已有决策，不重复处理
            if (winner.status() == targetStatus) {
                return toDecision(winner);
            }
            // 冲突
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

    /**
     * 根据 approvalId 寻找审批记录，顺便检查审批是否已经过期，并通过并发安全的方式把状态从 REQUESTED 更新为 EXPIRED。
     */
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

    /**
     * 读取审批记录时，顺便检查审批是否已经过期，并通过并发安全的方式把状态从 REQUESTED 更新为 EXPIRED。
     */
    private ApprovalRecord expireIfNecessary(ApprovalRecord current) {
        // 取当前时间，类似 Instant.now()，但是可以注入模拟时间
        Instant checkedAt = clock.instant();
        // 检查当前状态和过期时间：审批已经不是等待状态，直接返回，不能再把它改成过期 || 当前时间还早于过期时间
        if (current.status() != ApprovalStatus.REQUESTED || checkedAt.isBefore(current.expiresAt())) {
            // 没过期：原样返回
            return current;
        }
        // “仍在等待审批，并且已经过期”才会执行下面代码
        // 在内存中构造过期记录,只修改审批结果相关字段：status、处理者、原因、更新时间/决策时间
        ApprovalRecord expired = new ApprovalRecord(
                current.approvalId(), current.runId(), current.conversationId(), current.toolCallRequest(),
                current.reason(), ApprovalStatus.EXPIRED, "system", "approval expired",
                current.createdAt(), current.expiresAt(), checkedAt
        );
        // 用条件更新写入数据库，因为考虑审批人与过期检查同时发生
        if (approvalStore.expireIfRequested(current.approvalId(), expired, checkedAt)) {
            return expired;
        }
        return approvalStore.find(current.approvalId()).orElse(current);
    }
}
