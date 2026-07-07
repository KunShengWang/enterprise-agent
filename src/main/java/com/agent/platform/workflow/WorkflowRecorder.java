package com.agent.platform.workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowRecorder {

    void start(WorkflowExecutionPlan plan);

    void checkpoint(String traceId, WorkflowCheckpoint checkpoint);

    void finish(String traceId, WorkflowRunStatus status, String failureReason);

    Optional<WorkflowRunRecord> find(String traceId);

    List<WorkflowRunRecord> recent(int limit);
}
