package com.agent.platform.approval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Primary
@Component
public class JdbcApprovalStore implements ApprovalStore {

    private static final String CATEGORY = "approval";

    private final JdbcAgentStoreSupport store;

    public JdbcApprovalStore(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void save(ApprovalRecord record) {
        if (record == null || record.approvalId() == null || record.approvalId().isBlank()) {
            return;
        }
        Instant updatedAt = record.decidedAt() == null ? Instant.now() : record.decidedAt();
        store.save(CATEGORY, record.approvalId(), record, record.createdAt(), updatedAt);
    }

    @Override
    public boolean decideIfRequestedAndNotExpired(String approvalId,
                                                  ApprovalRecord nextRecord,
                                                  Instant decisionTime) {
        if (approvalId == null || approvalId.isBlank() || nextRecord == null || decisionTime == null) {
            return false;
        }
        Instant updatedAt = nextRecord.decidedAt() == null ? Instant.now() : nextRecord.decidedAt();
        return store.updateIfJsonFieldEqualsAndInstantAfter(
                CATEGORY,
                approvalId,
                "status",
                ApprovalStatus.REQUESTED.name(),
                "expiresAt",
                decisionTime,
                nextRecord,
                updatedAt
        );
    }

    @Override
    public boolean expireIfRequested(String approvalId,
                                     ApprovalRecord expiredRecord,
                                     Instant expirationCheckTime) {
        if (approvalId == null || approvalId.isBlank()
                || expiredRecord == null || expirationCheckTime == null) {
            return false;
        }
        Instant updatedAt = expiredRecord.decidedAt() == null ? Instant.now() : expiredRecord.decidedAt();
        return store.updateIfJsonFieldEqualsAndInstantAtOrBefore(
                CATEGORY,
                approvalId,
                "status",
                ApprovalStatus.REQUESTED.name(),
                "expiresAt",
                expirationCheckTime,
                expiredRecord,
                updatedAt
        );
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        return store.find(CATEGORY, approvalId, ApprovalRecord.class);
    }

    @Override
    public List<ApprovalRecord> recent(int limit) {
        return store.recent(CATEGORY, ApprovalRecord.class, limit);
    }
}
