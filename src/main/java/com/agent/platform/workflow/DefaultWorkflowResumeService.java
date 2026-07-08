package com.agent.platform.workflow;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultWorkflowResumeService implements WorkflowResumeService {

    private final WorkflowRecorder workflowRecorder;

    public DefaultWorkflowResumeService(WorkflowRecorder workflowRecorder) {
        this.workflowRecorder = workflowRecorder;
    }

    @Override
    public WorkflowResumeResult resume(String traceId) {
        WorkflowRunRecord record = workflowRecorder.find(traceId)
                .orElse(null);
        if (record == null) {
            return notResumable(traceId, "workflow run not found");
        }
        WorkflowCheckpoint checkpoint = lastResumableCheckpoint(record);
        if (checkpoint == null) {
            workflowRecorder.checkpoint(record.traceId(), new WorkflowCheckpoint(
                    WorkflowNode.FAILED,
                    "RESUME_REJECTED",
                    "No resumable checkpoint found",
                    false,
                    false,
                    Instant.now()
            ));
            return notResumable(traceId, "no resumable checkpoint found");
        }
        List<WorkflowNode> nodes = record.plan().nodes();
        int resumeIndex = Math.max(0, nodes.indexOf(checkpoint.node()));
        List<WorkflowNode> skipped = new ArrayList<>(nodes.subList(0, resumeIndex));
        List<WorkflowNode> remaining = new ArrayList<>(nodes.subList(resumeIndex, nodes.size()));
        workflowRecorder.checkpoint(record.traceId(), new WorkflowCheckpoint(
                checkpoint.node(),
                "RESUME_REQUESTED",
                "Resume requested from checkpoint node " + checkpoint.node(),
                checkpoint.retryable(),
                true,
                Instant.now()
        ));
        workflowRecorder.finish(record.traceId(), WorkflowRunStatus.RESUMABLE, "resume plan generated");
        return new WorkflowResumeResult(
                record.traceId(),
                true,
                checkpoint.node(),
                skipped,
                remaining,
                "resume plan generated from last resumable checkpoint",
                Instant.now()
        );
    }

    private WorkflowCheckpoint lastResumableCheckpoint(WorkflowRunRecord record) {
        List<WorkflowCheckpoint> checkpoints = record.checkpoints();
        for (int index = checkpoints.size() - 1; index >= 0; index--) {
            WorkflowCheckpoint checkpoint = checkpoints.get(index);
            if (checkpoint.resumable()) {
                return checkpoint;
            }
        }
        return null;
    }

    private WorkflowResumeResult notResumable(String traceId, String reason) {
        return new WorkflowResumeResult(
                traceId,
                false,
                null,
                List.of(),
                List.of(),
                reason,
                Instant.now()
        );
    }
}
