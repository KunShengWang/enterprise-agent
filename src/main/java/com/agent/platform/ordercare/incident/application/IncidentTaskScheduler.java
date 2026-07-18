package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.ToolExecutionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class IncidentTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(IncidentTaskScheduler.class);

    private final AgentTaskStore taskStore;
    private final EvidenceStore evidenceStore;
    private final IncidentTaskResultCommitter resultCommitter;
    private final AgentContinuationRuntime continuationRuntime;
    private final ToolExecutionStore toolExecutionStore;
    private final IncidentEvidenceProjector evidenceProjector;
    private final IncidentExecutionProfileFactory profileFactory;
    private final ExecutorService executor;

    public IncidentTaskScheduler(AgentTaskStore taskStore,
                                 EvidenceStore evidenceStore,
                                 IncidentTaskResultCommitter resultCommitter,
                                 AgentContinuationRuntime continuationRuntime,
                                 ToolExecutionStore toolExecutionStore,
                                 IncidentEvidenceProjector evidenceProjector,
                                 IncidentExecutionProfileFactory profileFactory,
                                 IncidentCommandProperties properties) {
        this.taskStore = taskStore;
        this.evidenceStore = evidenceStore;
        this.resultCommitter = resultCommitter;
        this.continuationRuntime = continuationRuntime;
        this.toolExecutionStore = toolExecutionStore;
        this.evidenceProjector = evidenceProjector;
        this.profileFactory = profileFactory;
        int workers = properties.getMaxParallelSpecialists();
        this.executor = new ThreadPoolExecutor(
                workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 2),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public List<IncidentTaskExecution> execute(List<AgentTaskRecord> tasks, IncidentSnapshot snapshot) {
        List<CompletableFuture<IncidentTaskExecution>> futures = new ArrayList<>();
        for (AgentTaskRecord task : tasks) {
            try {
                futures.add(CompletableFuture.supplyAsync(() -> executeSafely(task, snapshot), executor));
            }
            catch (RuntimeException rejected) {
                futures.add(CompletableFuture.completedFuture(failed(
                        task, "specialist executor queue rejected task")));
            }
        }
        return futures.stream().map(future -> {
            try {
                return future.join();
            }
            catch (RuntimeException exception) {
                return new IncidentTaskExecution(
                        null, List.of(),
                        List.of(new EvidenceGap("SPECIALIST_FAILED", "agent-runtime",
                                failureReason(exception))), false);
            }
        }).toList();
    }

    private IncidentTaskExecution executeSafely(AgentTaskRecord original,
                                                IncidentSnapshot snapshot) {
        try {
            return executeOne(original, snapshot);
        }
        catch (RuntimeException exception) {
            String reason = failureReason(exception);
            log.warn("incident specialist failed outside Runtime boundary: incidentId={}, taskId={}, reason={}",
                    original.incidentId(), original.taskId(), reason, exception);
            AgentTaskRecord current = taskStore.findTask(original.taskId()).orElse(original);
            if (current.status() == AgentTaskStatus.WAITING_CLARIFICATION
                    || current.status() == AgentTaskStatus.SUCCEEDED) {
                List<EvidenceRecord> committedEvidence = evidenceStore.listEvidence(current.incidentId()).stream()
                        .filter(item -> current.taskId().equals(item.taskId()))
                        .toList();
                if (!committedEvidence.isEmpty()) {
                    return new IncidentTaskExecution(current, committedEvidence, List.of(), true);
                }
            }
            if (current.status() == AgentTaskStatus.CLAIMED
                    || current.status() == AgentTaskStatus.RUNNING) {
                return retryOrFail(current, snapshot, reason);
            }
            return failed(current, reason);
        }
    }

    public IncidentTaskExecution clarify(AgentTaskRecord task,
                                         AgentFollowUpInput input) {
        AgentTaskRecord running = taskStore.transitionTask(
                task.taskId(), task.version(), AgentTaskStatus.RUNNING, task.childRunId(), "",
                TaskEventActorType.ORCHESTRATOR, "incident-orchestrator",
                "clarification-running:" + task.taskId() + ":" + task.version());
        AgentRuntimeResult result = continuationRuntime.continueWithInput(
                running.childRunId(), input, event -> { });
        if (result.state() != AgentRunState.COMPLETED) {
            return failed(running, "clarification run did not complete: " + result.state());
        }
        Set<String> existingToolCalls = evidenceStore.listEvidence(running.incidentId()).stream()
                .map(evidence -> String.valueOf(evidence.queryParameters().getOrDefault("toolCallId", "")))
                .collect(java.util.stream.Collectors.toSet());
        List<com.agent.platform.runtime.ToolExecutionRecord> toolExecutions =
                toolExecutionStore.findByRun(running.childRunId());
        List<EvidenceCandidate> additional = evidenceProjector.project(toolExecutions)
                .stream()
                .filter(candidate -> !existingToolCalls.contains(
                        String.valueOf(candidate.queryParameters().getOrDefault("toolCallId", ""))))
                .toList();
        if (additional.isEmpty()) {
            return failed(running, "clarification produced no new fact evidence");
        }
        TaskResultCommitResult committed = resultCommitter.commit(new TaskResultSubmission(
                running.incidentId(), running.taskId(), running.childRunId(), running.version(),
                "clarification-result:" + running.taskId() + ":" + running.version(),
                AgentTaskStatus.SUCCEEDED,
                Map.of("clarification", true, "answer", result.answer()),
                additional));
        return new IncidentTaskExecution(
                committed.task(), committed.evidence(), evidenceProjector.projectGaps(toolExecutions), true);
    }

    public AgentTaskRecord completeWithoutClarification(AgentTaskRecord task) {
        continuationRuntime.completeWaitingInput(task.childRunId());
        return taskStore.transitionTask(
                task.taskId(), task.version(), AgentTaskStatus.SUCCEEDED, task.childRunId(), "",
                TaskEventActorType.ORCHESTRATOR, "incident-orchestrator",
                "task-complete-no-clarification:" + task.taskId());
    }

    private IncidentTaskExecution executeOne(AgentTaskRecord original, IncidentSnapshot snapshot) {
        if (!Instant.now().isBefore(snapshot.deadlineAt())) {
            return cancelled(original, "incident deadline exceeded before specialist execution");
        }
        AgentTaskRecord claimed = taskStore.transitionTask(
                original.taskId(), original.version(), AgentTaskStatus.CLAIMED, null, "",
                TaskEventActorType.ORCHESTRATOR, "incident-task-scheduler",
                "task-claimed:" + original.taskId() + ":" + original.attempt());
        AgentTaskRecord running = taskStore.transitionTask(
                claimed.taskId(), claimed.version(), AgentTaskStatus.RUNNING, null, "",
                TaskEventActorType.ORCHESTRATOR, "incident-task-scheduler",
                "task-running:" + claimed.taskId() + ":" + claimed.attempt());
        AtomicReference<AgentTaskRecord> boundTask = new AtomicReference<>(running);
        IncidentAgentRole role = IncidentAgentRole.valueOf(running.role());
        String prompt = specialistPrompt(running, snapshot, role);
        AgentRuntimeResult result;
        try {
            result = continuationRuntime.runUntilInputCheckpoint(
                    new AgentRequest(
                            "incident:" + snapshot.incidentId() + ":task:" + running.taskId(),
                            "incident-specialist",
                            prompt,
                            Map.of(
                                    "incidentId", snapshot.incidentId(),
                                    "parentIncidentId", snapshot.incidentId(),
                                    "runRole", "SPECIALIST",
                                    "taskId", running.taskId(),
                                    "snapshotId", snapshot.snapshotId()),
                            "ordercare-incident-command-v1"),
                    profileFactory.specialist(role),
                    event -> {
                        if (event.type() == AgentEventType.RUN_STARTED) {
                            AgentTaskRecord current = boundTask.get();
                            AgentTaskRecord bound = taskStore.bindChildRun(
                                    current.taskId(), current.version(), event.runId(),
                                    "bind-child-run:" + current.taskId() + ":" + current.attempt());
                            boundTask.set(bound);
                        }
                    });
        }
        catch (RuntimeException exception) {
            return retryOrFail(boundTask.get(), snapshot,
                    "specialist runtime failed: " + exception.getClass().getSimpleName());
        }
        AgentTaskRecord owned = boundTask.get();
        if (result.state() != AgentRunState.WAITING_INPUT) {
            return retryOrFail(owned, snapshot, "specialist stopped in state " + result.state());
        }
        List<com.agent.platform.runtime.ToolExecutionRecord> toolExecutions =
                toolExecutionStore.findByRun(result.runId());
        List<EvidenceCandidate> candidates = evidenceProjector.project(toolExecutions);
        if (candidates.isEmpty()) {
            return retryOrFail(owned, snapshot, "specialist returned no successful read-only fact tool result");
        }
        TaskResultCommitResult committed = resultCommitter.commit(new TaskResultSubmission(
                owned.incidentId(), owned.taskId(), result.runId(), owned.version(),
                "specialist-result:" + owned.taskId() + ":" + owned.attempt(),
                AgentTaskStatus.WAITING_CLARIFICATION,
                Map.of("answer", result.answer(), "evidenceCount", candidates.size()),
                candidates));
        return new IncidentTaskExecution(
                committed.task(), committed.evidence(), evidenceProjector.projectGaps(toolExecutions), true);
    }

    private IncidentTaskExecution retryOrFail(AgentTaskRecord task,
                                              IncidentSnapshot snapshot,
                                              String reason) {
        closeWaitingCheckpoint(task);
        if (task != null && task.attempt() + 1 < task.maxAttempts()) {
            AgentTaskRecord retry = taskStore.transitionTask(
                    task.taskId(), task.version(), AgentTaskStatus.RETRY_PENDING, task.childRunId(), reason,
                    TaskEventActorType.RUNTIME, "incident-task-scheduler",
                    "task-retry:" + task.taskId() + ":" + task.attempt());
            return executeOne(retry, snapshot);
        }
        return failed(task, reason);
    }

    private IncidentTaskExecution failed(AgentTaskRecord task, String reason) {
        AgentTaskRecord failed = task;
        if (task != null && !task.status().terminal()) {
            AgentTaskStatus target = switch (task.status()) {
                case CLAIMED, RUNNING -> AgentTaskStatus.FAILED;
                case PENDING, RETRY_PENDING, WAITING_CLARIFICATION -> AgentTaskStatus.CANCELLED;
                default -> null;
            };
            if (target != null) {
            failed = taskStore.transitionTask(
                    task.taskId(), task.version(), target, task.childRunId(), reason,
                    TaskEventActorType.RUNTIME, "incident-task-scheduler",
                    "task-failed:" + task.taskId() + ":" + task.version());
            }
        }
        return new IncidentTaskExecution(
                failed, List.of(), List.of(new EvidenceGap("SPECIALIST_FAILED", "agent-runtime", reason)), false);
    }

    private IncidentTaskExecution cancelled(AgentTaskRecord task, String reason) {
        AgentTaskRecord cancelled = taskStore.transitionTask(
                task.taskId(), task.version(), AgentTaskStatus.CANCELLED, task.childRunId(), reason,
                TaskEventActorType.RUNTIME, "incident-task-scheduler",
                "task-cancelled:" + task.taskId() + ":" + task.version());
        return new IncidentTaskExecution(
                cancelled, List.of(), List.of(new EvidenceGap("INCIDENT_DEADLINE_EXCEEDED", "scheduler", reason)), false);
    }

    private void closeWaitingCheckpoint(AgentTaskRecord task) {
        if (task == null || task.childRunId() == null || task.childRunId().isBlank()) {
            return;
        }
        try {
            continuationRuntime.completeWaitingInput(task.childRunId());
        }
        catch (RuntimeException ignored) {
            // A failed/cancelled Run is already terminal; only WAITING_INPUT needs explicit closure.
        }
    }

    private String failureReason(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private String specialistPrompt(AgentTaskRecord task,
                                    IncidentSnapshot snapshot,
                                    IncidentAgentRole role) {
        if (role == IncidentAgentRole.SOP_ANALYST) {
            return "查询与以下事故相关的版本化故障处置和升级 SOP。只返回只读建议。事故症状："
                    + task.objective() + "。scopeHash=" + snapshot.scopeHash();
        }
        return """
                调查任务：%s
                角色：%s
                必须调用唯一允许的只读能力，参数只能是：{"snapshotId":"%s"}。
                不得提交 requestIds、queueNames、URL 或写操作。完成后返回 specialist-report-v1 JSON。
                """.formatted(task.objective(), role, snapshot.snapshotId()).trim();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
