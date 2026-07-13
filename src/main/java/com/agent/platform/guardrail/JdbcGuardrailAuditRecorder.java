package com.agent.platform.guardrail;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;

@Primary
@Repository
public class JdbcGuardrailAuditRecorder implements GuardrailAuditRecorder {

    private static final String CATEGORY = "guardrail_audit";

    private final JdbcAgentStoreSupport store;

    public JdbcGuardrailAuditRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void record(GuardrailAuditRecord record) {
        if (record != null) {
            store.save(CATEGORY, record.auditId(), record, record.createdAt(), record.createdAt());
        }
    }

    @Override
    public List<GuardrailAuditRecord> recent(int limit) {
        return store.recent(CATEGORY, GuardrailAuditRecord.class, limit);
    }

    @Override
    public void clear() {
        store.clear(CATEGORY);
    }
}
