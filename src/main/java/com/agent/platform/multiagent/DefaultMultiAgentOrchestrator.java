package com.agent.platform.multiagent;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.runtime.AgentTimelineStore;
import com.agent.platform.workflow.WorkflowNode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于统一 AgentRuntime 的隔离式 Sub-Agent 编排器。
 *
 * <p>编排器只负责任务生命周期和结果汇合；执行配置、结构化协议和子 Agent 运行分别由
 * ProfileFactory、Protocol、Runner 承担，避免重新形成另一个巨型 Executor。</p>
 */
@Service
public class DefaultMultiAgentOrchestrator implements MultiAgentOrchestrator {

    private static final int MAX_SPECIALISTS = 2;
    private static final long SPECIALIST_TIMEOUT_SECONDS = 60;

    private final AgentRunStore runStore;
    private final AgentTimelineStore timelineStore;
    private final SubAgentRunner subAgentRunner;
    private final SubAgentProfileFactory profileFactory;
    private final SubAgentProtocol protocol;
    private final ThreadPoolExecutor specialistExecutor;

    public DefaultMultiAgentOrchestrator(AgentRunStore runStore,
                                         AgentTimelineStore timelineStore,
                                         SubAgentRunner subAgentRunner,
                                         SubAgentProfileFactory profileFactory,
                                         SubAgentProtocol protocol) {
        this.runStore = runStore;
        this.timelineStore = timelineStore;
        this.subAgentRunner = subAgentRunner;
        this.profileFactory = profileFactory;
        this.protocol = protocol;
        AtomicInteger sequence = new AtomicInteger();
        this.specialistExecutor = new ThreadPoolExecutor(
                MAX_SPECIALISTS,
                MAX_SPECIALISTS,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "sub-agent-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.specialistExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    public MultiAgentRunResponse execute(AgentRequest request) {
        validate(request);
        Instant startedAt = Instant.now();
        String coordinatorRunId = UUID.randomUUID().toString();
        String parentConversationId = normalize(request.conversationId(), "multi-agent");
        String coordinatorSessionId = parentConversationId + ":multi:" + coordinatorRunId;
        String userId = normalize(request.userId(), "anonymous");
        AgentRequest coordinatorRequest = new AgentRequest(
                coordinatorSessionId, userId, request.question(), request.metadata()
        );
        openCoordinator(coordinatorRunId, coordinatorSessionId, parentConversationId, userId, coordinatorRequest);

        try {
            SubAgentExecutionResult planner = subAgentRunner.run(
                    coordinatorRunId,
                    coordinatorSessionId,
                    userId,
                    "planner",
                    MultiAgentRole.PLANNER,
                    protocol.plannerInstruction(request.question()),
                    profileFactory.planner()
            );
            List<MultiAgentTask> specialistTasks = protocol.parsePlannerTasks(planner.answer(), request.question());
            List<SubAgentExecutionResult> specialists = runSpecialists(
                    coordinatorRunId, coordinatorSessionId, userId, request.question(), specialistTasks
            );
            SubAgentExecutionResult reviewer = subAgentRunner.run(
                    coordinatorRunId,
                    coordinatorSessionId,
                    userId,
                    "reviewer",
                    MultiAgentRole.REVIEWER,
                    protocol.reviewerInstruction(request.question(), specialists),
                    profileFactory.reviewer()
            );
            MultiAgentReviewResult review = protocol.parseReview(reviewer.answer(), specialists);

            List<MultiAgentTask> tasks = responseTasks(planner, specialistTasks, reviewer);
            List<MultiAgentMessage> messages = new ArrayList<>();
            messages.add(planner.message());
            specialists.forEach(result -> messages.add(result.message()));
            messages.add(reviewer.message());
            Map<String, Object> metrics = metrics(messages, specialists, review);
            finishCoordinator(coordinatorRunId, coordinatorSessionId, userId, review, specialistTasks, metrics);

            Instant finishedAt = Instant.now();
            return new MultiAgentRunResponse(
                    coordinatorRunId,
                    parentConversationId,
                    request.question(),
                    review.finalAnswer(),
                    tasks,
                    messages,
                    startedAt,
                    finishedAt,
                    Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
                    metrics
            );
        }
        catch (RuntimeException exception) {
            failCoordinator(coordinatorRunId, coordinatorSessionId, userId, exception);
            throw exception;
        }
    }

    private List<SubAgentExecutionResult> runSpecialists(String coordinatorRunId,
                                                         String coordinatorSessionId,
                                                         String userId,
                                                         String question,
                                                         List<MultiAgentTask> tasks) {
        List<Callable<SubAgentExecutionResult>> calls = tasks.stream()
                .limit(MAX_SPECIALISTS)
                .map(task -> (Callable<SubAgentExecutionResult>) () -> subAgentRunner.run(
                        coordinatorRunId,
                        coordinatorSessionId,
                        userId,
                        task.taskId(),
                        task.role(),
                        "用户问题：" + question + "\n你的专项任务：" + task.instruction(),
                        profileFactory.specialist(task.role())
                ))
                .toList();
        try {
            List<Future<SubAgentExecutionResult>> futures = specialistExecutor.invokeAll(
                    calls, SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
            List<SubAgentExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Future<SubAgentExecutionResult> future = futures.get(index);
                MultiAgentTask task = tasks.get(index);
                if (future.isCancelled()) {
                    results.add(subAgentRunner.timeout(task));
                    continue;
                }
                try {
                    results.add(future.get());
                }
                catch (Exception exception) {
                    results.add(subAgentRunner.failure(task, exception.getCause()));
                }
            }
            return List.copyOf(results);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("specialist execution interrupted", exception);
        }
    }

    private void openCoordinator(String runId,
                                 String sessionId,
                                 String parentConversationId,
                                 String userId,
                                 AgentRequest request) {
        timelineStore.openSession(sessionId, userId);
        runStore.create(AgentRunRecord.create(runId, runId, sessionId, request));
        appendEvent(sessionId, userId, runId, AgentEventType.RUN_STARTED,
                "multi-agent coordinator started", Map.of("parentConversationId", parentConversationId));
    }

    private void finishCoordinator(String runId,
                                   String sessionId,
                                   String userId,
                                   MultiAgentReviewResult review,
                                   List<MultiAgentTask> specialistTasks,
                                   Map<String, Object> metrics) {
        boolean usedRag = specialistTasks.stream().anyMatch(task -> task.role() == MultiAgentRole.RAG_WORKER);
        runStore.update(runId, current -> current.finished(
                AgentRunState.COMPLETED,
                WorkflowNode.FINISH,
                review.finalAnswer(),
                "",
                List.of(),
                List.of(),
                usedRag,
                false
        ));
        appendEvent(sessionId, userId, runId, AgentEventType.RUN_COMPLETED,
                "multi-agent coordinator completed",
                Map.of(
                        "state", AgentRunState.COMPLETED.name(),
                        "stopReason", AgentStopReason.COMPLETED.name(),
                        "metrics", metrics
                ));
    }

    private void failCoordinator(String runId,
                                 String sessionId,
                                 String userId,
                                 RuntimeException exception) {
        runStore.update(runId, current -> current.finished(
                AgentRunState.FAILED,
                WorkflowNode.FAILED,
                "Multi-Agent 编排失败，请稍后重试。",
                exception.getClass().getSimpleName(),
                List.of(),
                List.of(),
                false,
                false
        ));
        appendEvent(sessionId, userId, runId, AgentEventType.RUN_FAILED,
                "multi-agent coordinator failed",
                Map.of(
                        "state", AgentRunState.FAILED.name(),
                        "stopReason", AgentStopReason.INTERNAL_ERROR.name(),
                        "errorType", exception.getClass().getSimpleName()
                ));
    }

    private List<MultiAgentTask> responseTasks(SubAgentExecutionResult planner,
                                               List<MultiAgentTask> specialists,
                                               SubAgentExecutionResult reviewer) {
        List<MultiAgentTask> tasks = new ArrayList<>();
        tasks.add(new MultiAgentTask("planner", MultiAgentRole.PLANNER,
                "根据用户问题生成受约束的 specialist 任务", Map.of("childRunId", planner.childRunId())));
        tasks.addAll(specialists);
        tasks.add(new MultiAgentTask("reviewer", MultiAgentRole.REVIEWER,
                "只基于 specialist 摘要审查冲突并生成最终回答",
                Map.of("childRunId", reviewer.childRunId())));
        return List.copyOf(tasks);
    }

    private Map<String, Object> metrics(List<MultiAgentMessage> messages,
                                        List<SubAgentExecutionResult> specialists,
                                        MultiAgentReviewResult review) {
        List<String> childRunIds = messages.stream()
                .map(message -> String.valueOf(message.metadata().getOrDefault("childRunId", "")))
                .filter(value -> !value.isBlank())
                .toList();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("executionModel", "isolated-sub-agent-runtime");
        metrics.put("childRunIds", childRunIds);
        metrics.put("specialistCount", specialists.size());
        metrics.put("reviewApproved", review.approved());
        metrics.put("reviewConfidence", review.confidence());
        metrics.put("conflictDetected", review.conflictDetected());
        metrics.put("fullChildContextMerged", false);
        return Map.copyOf(metrics);
    }

    private void appendEvent(String sessionId,
                             String userId,
                             String runId,
                             AgentEventType type,
                             String content,
                             Map<String, Object> payload) {
        timelineStore.appendEvent(sessionId, userId, runId, new AgentEventDraft(type, content, payload));
    }

    private void validate(AgentRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("multi-agent question must not be blank");
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @PreDestroy
    public void shutdownExecutor() {
        specialistExecutor.shutdownNow();
    }
}
