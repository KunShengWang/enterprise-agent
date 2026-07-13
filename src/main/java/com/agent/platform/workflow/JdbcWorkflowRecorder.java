package com.agent.platform.workflow;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Primary
@Component
public class JdbcWorkflowRecorder implements WorkflowRecorder {

    private static final String CATEGORY = "workflow";

    private final JdbcAgentStoreSupport store;

    public JdbcWorkflowRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void start(WorkflowExecutionPlan plan) {
        if (plan == null || plan.traceId() == null || plan.traceId().isBlank()) {
            return;
        }
        WorkflowRunRecord record = new WorkflowRunRecord(
                plan.traceId(),
                plan.conversationId(),
                WorkflowRunStatus.RUNNING,
                plan,
                List.of(),
                "",
                Instant.now()
        );
        store.save(CATEGORY, plan.traceId(), record, plan.createdAt(), record.updatedAt());
    }

    @Override
    public void checkpoint(String traceId, WorkflowCheckpoint checkpoint) {
        if (traceId == null || traceId.isBlank() || checkpoint == null) {
            return;
        }
        WorkflowRunRecord current = find(traceId).orElse(null);
        if (current == null) {
            return;
        }
        List<WorkflowCheckpoint> checkpoints = new ArrayList<>(current.checkpoints());
        checkpoints.add(checkpoint);
        WorkflowRunRecord updated = new WorkflowRunRecord(
                current.traceId(),
                current.conversationId(),
                current.status(),
                current.plan(),
                checkpoints,
                current.failureReason(),
                Instant.now()
        );
        store.save(CATEGORY, traceId, updated, current.plan().createdAt(), updated.updatedAt());
    }

    @Override
    public void finish(String traceId, WorkflowRunStatus status, String failureReason) {
        WorkflowRunRecord current = find(traceId).orElse(null);
        if (current == null) {
            return;
        }
        WorkflowRunRecord updated = new WorkflowRunRecord(
                current.traceId(),
                current.conversationId(),
                status == null ? WorkflowRunStatus.COMPLETED : status,
                current.plan(),
                current.checkpoints(),
                failureReason == null ? "" : failureReason,
                Instant.now()
        );
        store.save(CATEGORY, traceId, updated, current.plan().createdAt(), updated.updatedAt());
    }

    @Override
    public Optional<WorkflowRunRecord> find(String traceId) {
        return store.find(CATEGORY, traceId, WorkflowRunRecord.class);
    }

    @Override
    public List<WorkflowRunRecord> recent(int limit) {
        return store.recent(CATEGORY, WorkflowRunRecord.class, limit);
    }
}
