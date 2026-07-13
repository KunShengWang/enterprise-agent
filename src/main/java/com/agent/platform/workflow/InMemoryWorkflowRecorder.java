package com.agent.platform.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Deprecated(forRemoval = true)
public class InMemoryWorkflowRecorder implements WorkflowRecorder {

    private static final int MAX_RECORDS = 500;

    private final ConcurrentMap<String, MutableWorkflowRun> runs = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> recentTraceIds = new ConcurrentLinkedDeque<>();

    @Override
    public void start(WorkflowExecutionPlan plan) {
        if (plan == null || plan.traceId() == null || plan.traceId().isBlank()) {
            return;
        }
        runs.put(plan.traceId(), new MutableWorkflowRun(plan));
        recentTraceIds.addFirst(plan.traceId());
        while (recentTraceIds.size() > MAX_RECORDS) {
            String removed = recentTraceIds.pollLast();
            if (removed != null) {
                runs.remove(removed);
            }
        }
    }

    @Override
    public void checkpoint(String traceId, WorkflowCheckpoint checkpoint) {
        MutableWorkflowRun run = runs.get(traceId);
        if (run != null && checkpoint != null) {
            run.checkpoints.add(checkpoint);
            run.updatedAt = Instant.now();
        }
    }

    @Override
    public void finish(String traceId, WorkflowRunStatus status, String failureReason) {
        MutableWorkflowRun run = runs.get(traceId);
        if (run != null) {
            run.status = status == null ? WorkflowRunStatus.COMPLETED : status;
            run.failureReason = failureReason == null ? "" : failureReason;
            run.updatedAt = Instant.now();
        }
    }

    @Override
    public Optional<WorkflowRunRecord> find(String traceId) {
        return Optional.ofNullable(runs.get(traceId)).map(MutableWorkflowRun::snapshot);
    }

    @Override
    public List<WorkflowRunRecord> recent(int limit) {
        List<WorkflowRunRecord> result = new ArrayList<>();
        for (String traceId : recentTraceIds) {
            MutableWorkflowRun run = runs.get(traceId);
            if (run != null) {
                result.add(run.snapshot());
            }
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    private static final class MutableWorkflowRun {
        private final WorkflowExecutionPlan plan;
        private final List<WorkflowCheckpoint> checkpoints = new ArrayList<>();
        private WorkflowRunStatus status = WorkflowRunStatus.RUNNING;
        private String failureReason = "";
        private Instant updatedAt = Instant.now();

        private MutableWorkflowRun(WorkflowExecutionPlan plan) {
            this.plan = plan;
        }

        private WorkflowRunRecord snapshot() {
            return new WorkflowRunRecord(plan.traceId(), plan.conversationId(), status, plan, checkpoints, failureReason, updatedAt);
        }
    }
}
