package com.agent.platform.approval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
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
    public Optional<ApprovalRecord> find(String approvalId) {
        return store.find(CATEGORY, approvalId, ApprovalRecord.class);
    }

    @Override
    public List<ApprovalRecord> recent(int limit) {
        return store.recent(CATEGORY, ApprovalRecord.class, limit);
    }
}
