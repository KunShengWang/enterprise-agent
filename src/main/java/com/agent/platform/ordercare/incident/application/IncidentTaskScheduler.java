package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.config.IncidentWorkerIdentity;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskLeaseClaim;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.workbench.budget.BudgetExceededException;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final IncidentCommandProperties properties;
    private final IncidentWorkerIdentity workerIdentity;
    private final IncidentBudgetGate budgets;
    private final ExecutorService executor;
    private final ScheduledExecutorService leaseHeartbeatExecutor;

    @Autowired
    public IncidentTaskScheduler(AgentTaskStore taskStore,
                                 EvidenceStore evidenceStore,
                                 IncidentTaskResultCommitter resultCommitter,
                                 AgentContinuationRuntime continuationRuntime,
                                 ToolExecutionStore toolExecutionStore,
                                 IncidentEvidenceProjector evidenceProjector,
                                 IncidentExecutionProfileFactory profileFactory,
                                 IncidentCommandProperties properties,
                                 IncidentWorkerIdentity workerIdentity,
                                 IncidentBudgetGate budgets) {
        this.taskStore = taskStore;
        this.evidenceStore = evidenceStore;
        this.resultCommitter = resultCommitter;
        this.continuationRuntime = continuationRuntime;
        this.toolExecutionStore = toolExecutionStore;
        this.evidenceProjector = evidenceProjector;
        this.profileFactory = profileFactory;
        this.properties = properties;
        this.workerIdentity = workerIdentity;
        this.budgets = budgets;
        int workers = properties.getMaxParallelSpecialists();
        this.executor = new ThreadPoolExecutor(
                workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 2),
                new ThreadPoolExecutor.AbortPolicy());
        this.leaseHeartbeatExecutor = Executors.newScheduledThreadPool(Math.min(workers, 2));
    }

    public IncidentTaskScheduler(AgentTaskStore taskStore,
                                 EvidenceStore evidenceStore,
                                 IncidentTaskResultCommitter resultCommitter,
                                 AgentContinuationRuntime continuationRuntime,
                                 ToolExecutionStore toolExecutionStore,
                                 IncidentEvidenceProjector evidenceProjector,
                                 IncidentExecutionProfileFactory profileFactory,
                                 IncidentCommandProperties properties,
                                 IncidentWorkerIdentity workerIdentity) {
        this(taskStore, evidenceStore, resultCommitter, continuationRuntime, toolExecutionStore,
                evidenceProjector, profileFactory, properties, workerIdentity, IncidentBudgetGate.NOOP);
    }

    public List<IncidentTaskExecution> execute(List<AgentTaskRecord> tasks, IncidentSnapshot snapshot) {
        List<CompletableFuture<IncidentTaskExecution>> futures = new ArrayList<>();
        for (AgentTaskRecord task : tasks) {
            try {
                // 每个任务提交到线程池：异步并行执行（fork）
                futures.add(CompletableFuture.supplyAsync(() -> executeSafely(task, snapshot), executor));
            }
            catch (RuntimeException rejected) {
                futures.add(CompletableFuture.completedFuture(failed(
                        task, "specialist executor queue rejected task")));
            }
        }
        // 逐个等待完成（join）：阻塞直到所有任务都结束（join）
        return futures.stream().map(future -> {
            try {
                return future.join();// ← 这里会阻塞等待这个任务完成
            }
            catch (RuntimeException exception) {
                // 失败也返回结果，不中断
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
        IncidentAgentRole role = IncidentAgentRole.valueOf(running.role());
        IncidentBudgetReservation budget = budgets.reserveIncidentRun(
                running.incidentId(), specialistOperation(running), running.role(),
                profileFactory.specialist(role));
        AgentRuntimeResult result = continuationRuntime.continueWithInput(
                running.childRunId(), input, event -> { });
        budgets.settle(budget, result);
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
                additional, leaseOwner(running), running.fencingToken()));
        return new IncidentTaskExecution(
                committed.task(), committed.evidence(), evidenceProjector.projectGaps(toolExecutions), true);
    }

    public AgentTaskRecord completeWithoutClarification(AgentTaskRecord task) {
        IncidentAgentRole role = IncidentAgentRole.valueOf(task.role());
        IncidentBudgetReservation budget = budgets.reserveIncidentRun(
                task.incidentId(), specialistOperation(task), task.role(), profileFactory.specialist(role));
        continuationRuntime.completeWaitingInput(task.childRunId());
        budgets.settleStored(budget, task.childRunId());
        return taskStore.transitionTask(
                task.taskId(), task.version(), AgentTaskStatus.SUCCEEDED, task.childRunId(), "",
                TaskEventActorType.ORCHESTRATOR, "incident-orchestrator",
                "task-complete-no-clarification:" + task.taskId());
    }

    private IncidentTaskExecution executeOne(AgentTaskRecord original, IncidentSnapshot snapshot) {
        if (!Instant.now().isBefore(snapshot.deadlineAt())) {
            return cancelled(original, "incident deadline exceeded before specialist execution");
        }
        AgentTaskRecord claimed;
        if (properties.isPhase3Enabled()) {
            if (original.status() == AgentTaskStatus.CLAIMED
                    && original.leaseOwnedBy(workerIdentity.value(), original.fencingToken(), Instant.now())) {
                claimed = original;
            } else {
                TaskLeaseClaim lease = taskStore.claimTask(
                        original.taskId(), original.version(), workerIdentity.value(),
                        Instant.now().plusSeconds(properties.getTaskLeaseSeconds()), false);
                if (!lease.claimed()) {
                    return new IncidentTaskExecution(
                            lease.task(), List.of(),
                            List.of(new EvidenceGap("TASK_LEASE_BUSY", "scheduler",
                                    "task lease is owned by another instance")), false);
                }
                claimed = lease.task();
            }
        } else {
            claimed = taskStore.transitionTask(
                    original.taskId(), original.version(), AgentTaskStatus.CLAIMED, null, "",
                    TaskEventActorType.ORCHESTRATOR, "incident-task-scheduler",
                    "task-claimed:" + original.taskId() + ":" + original.attempt());
        }
        AgentTaskRecord running = properties.isPhase3Enabled()
                ? taskStore.transitionLeasedTask(
                        claimed.taskId(), claimed.version(), AgentTaskStatus.RUNNING, null, "",
                        workerIdentity.value(), claimed.fencingToken(), TaskEventActorType.ORCHESTRATOR,
                        "incident-task-scheduler", "task-running:" + claimed.taskId() + ":" + claimed.attempt()
                                + ":" + claimed.fencingToken())
                : taskStore.transitionTask(
                        claimed.taskId(), claimed.version(), AgentTaskStatus.RUNNING, null, "",
                        TaskEventActorType.ORCHESTRATOR, "incident-task-scheduler",
                        "task-running:" + claimed.taskId() + ":" + claimed.attempt());
        AtomicReference<AgentTaskRecord> boundTask = new AtomicReference<>(running);
        IncidentAgentRole role = IncidentAgentRole.valueOf(running.role());
        String prompt = specialistPrompt(running, snapshot, role);
        AgentExecutionProfile profile = profileFactory.specialist(role);
        String parentRunId = String.valueOf(
                running.inputPayload().getOrDefault("parentRunId", snapshot.incidentId()));
        int delegationDepth = running.inputPayload().get("delegationDepth") instanceof Number number
                ? number.intValue()
                : 0;
        IncidentBudgetReservation budget;
        try {
            budget = budgets.reserveIncidentRun(
                    running.incidentId(), specialistOperation(running), running.role(), profile);
        }
        catch (BudgetExceededException exhausted) {
            return failed(running, exhausted.code() + ": " + exhausted.getMessage());
        }
        AgentRuntimeResult result;
        ScheduledFuture<?> heartbeat = startHeartbeat(running);
        try {
            result = continuationRuntime.runUntilInputCheckpoint(
                    new AgentRequest(
                            "incident:" + snapshot.incidentId() + ":task:" + running.taskId(),
                            "incident-specialist",
                            prompt,
                            Map.of(
                                    "incidentId", snapshot.incidentId(),
                                    "parentIncidentId", snapshot.incidentId(),
                                    "parentRunId", parentRunId,
                                    "runRole", "SPECIALIST",
                                    "subAgentRole", role.name(),
                                    "internalSubAgent", true,
                                    "delegationDepth", delegationDepth,
                                    "taskId", running.taskId(),
                                    "snapshotId", snapshot.snapshotId()),
                            "ordercare-incident-command-v1"),
                    profile,
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
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
        if (result.state() != AgentRunState.WAITING_INPUT) budgets.settle(budget, result);
        AgentTaskRecord owned = boundTask.get();
        if (result.state() != AgentRunState.WAITING_INPUT) {
            IncidentTaskExecution recovered = recoverPersistedFactsAfterDuplicateToolRequest(
                    owned, result);
            if (recovered != null) {
                return recovered;
            }
            String stoppedReason =
                    "specialist stopped in state " + result.state() + ": " + result.stopReason();
            if (isRetryableStopReason(result.stopReason())) {
                return retryOrFail(owned, snapshot, stoppedReason);
            }
            closeWaitingCheckpoint(owned);
            return failed(owned, stoppedReason);
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
                candidates, leaseOwner(owned), owned.fencingToken()));
        return new IncidentTaskExecution(
                committed.task(), committed.evidence(), evidenceProjector.projectGaps(toolExecutions), true);
    }

    /**
     * Specialist profiles expose exactly one composite read-only capability. If the model violates the
     * stop contract and requests it again, Runtime must still keep the failed Run for an honest trace.
     * The first successful ToolExecution is already durable, however, so retrying the whole Agent would
     * duplicate an external read and create a misleading second child Run. Commit those durable facts as
     * the task result instead. This recovery is intentionally limited to TOOL_BUDGET_EXHAUSTED and never
     * turns a failed write/tool execution into success.
     */
    IncidentTaskExecution recoverPersistedFactsAfterDuplicateToolRequest(
            AgentTaskRecord task,
            AgentRuntimeResult result) {
        if (result.stopReason() != AgentStopReason.TOOL_BUDGET_EXHAUSTED) {
            return null;
        }
        List<com.agent.platform.runtime.ToolExecutionRecord> toolExecutions =
                toolExecutionStore.findByRun(result.runId());
        List<EvidenceCandidate> candidates = evidenceProjector.project(toolExecutions);
        if (candidates.isEmpty()) {
            return null;
        }

        log.warn("incident specialist requested its single read-only capability more than once; "
                        + "committing already persisted facts without retry: incidentId={}, taskId={}, runId={}, evidenceCount={}",
                task.incidentId(), task.taskId(), result.runId(), candidates.size());
        TaskResultCommitResult committed = resultCommitter.commit(new TaskResultSubmission(
                task.incidentId(), task.taskId(), result.runId(), task.version(),
                "specialist-result-recovered:" + task.taskId() + ":" + task.attempt(),
                AgentTaskStatus.SUCCEEDED,
                Map.of(
                        "answer", result.answer(),
                        "evidenceCount", candidates.size(),
                        "recoveredFromDuplicateToolRequest", true,
                        "runtimeStopReason", result.stopReason().name(),
                        "recoveryReason", "successful read-only facts were persisted before the duplicate request"),
                candidates, leaseOwner(task), task.fencingToken()));
        return new IncidentTaskExecution(
                committed.task(), committed.evidence(), evidenceProjector.projectGaps(toolExecutions), true);
    }

    static boolean isRetryableStopReason(AgentStopReason stopReason) {
        return stopReason == AgentStopReason.MODEL_ERROR
                || stopReason == AgentStopReason.TOOL_ERROR
                || stopReason == AgentStopReason.TIMEOUT
                || stopReason == AgentStopReason.INTERNAL_ERROR;
    }

    private String specialistOperation(AgentTaskRecord task) {
        return "specialist:" + task.taskId() + ":attempt:" + task.attempt();
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

    private String leaseOwner(AgentTaskRecord task) {
        if (!properties.isPhase3Enabled() || task == null) return "";
        return task.leaseOwnedBy(workerIdentity.value(), task.fencingToken(), Instant.now())
                ? workerIdentity.value()
                : "";
    }

    private ScheduledFuture<?> startHeartbeat(AgentTaskRecord task) {
        if (!properties.isPhase3Enabled() || task == null || task.fencingToken() <= 0) return null;
        long interval = Math.max(1, properties.getLeaseHeartbeatSeconds());
        return leaseHeartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                taskStore.renewTaskLease(task.taskId(), workerIdentity.value(), task.fencingToken(),
                        Instant.now().plusSeconds(properties.getTaskLeaseSeconds()));
            } catch (RuntimeException exception) {
                log.warn("incident task lease heartbeat rejected: taskId={}, token={}",
                        task.taskId(), task.fencingToken(), exception);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    String specialistPrompt(AgentTaskRecord task,
                            IncidentSnapshot snapshot,
                            IncidentAgentRole role) {
        // The question is checked by the input guardrail as untrusted task data. Trusted execution rules
        // (which capability to call, exact-once use and output format) belong to AgentExecutionProfile.systemPrompt.
        // Mixing them here makes a semantic injection classifier correctly suspicious of the text shape.
        return """
                事故调查任务数据
                objective: %s
                role: %s
                snapshotId: %s
                scopeHash: %s
                """.formatted(task.objective(), role, snapshot.snapshotId(), snapshot.scopeHash()).trim();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        leaseHeartbeatExecutor.shutdownNow();
    }
}
