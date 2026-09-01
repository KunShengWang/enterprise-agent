package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.llm.LlmCallException;
import com.agent.platform.llm.LlmCostCalculator;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单一模型驱动 Agent Runtime。
 *
 * <p>同步接口与 SSE 接口只需选择不同的 AgentEventListener，执行语义完全一致。
 * 每一次模型调用、工具策略、工具执行和终止原因都先写入数据库事件时间线。</p>
 */
@Service
public class DefaultAgentRuntime implements AgentRuntime, AgentContinuationRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultAgentRuntime.class);

    private static final String DEFAULT_SESSION_ID = "default-conversation";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final int MAX_RETURNED_EVENTS = 10_000;

    private final AgentProperties properties;
    private final AgentTimelineStore timelineStore;
    private final AgentRunStore runStore;
    private final ToolExecutionStore toolExecutionStore;
    private final AgentContextManager contextManager;
    private final AgentModelGateway modelGateway;
    private final AgentCapabilityRegistry capabilityRegistry;
    private final AgentToolRuntime toolRuntime;
    private final GuardrailService guardrailService;
    private final List<AgentFollowUpGuardrailPolicy> followUpGuardrailPolicies;
    private final ApprovalService approvalService;
    private final TokenEstimator tokenEstimator;
    private final AgentRunControlStore runControlStore;

    private final MemoryService memoryService;
    private final LlmCostCalculator costCalculator;
    private final ToolResultProjector toolResultProjector;

    /** 仅保存本实例中正在执行的取消句柄，持久取消事实仍写入 AgentRunControlStore。 */
    private final ConcurrentMap<String, AgentRunBudget> activeBudgets = new ConcurrentHashMap<>();
    /** 只在同步创建 Specialist Run 的调用栈内生效，真实续跑配置仍持久化到 AgentRunRecord。 */
    private final ThreadLocal<Integer> requestedInputCheckpoints = ThreadLocal.withInitial(() -> 0);
    private final AtomicInteger parallelToolThreadSequence = new AtomicInteger();
    private final ExecutorService parallelSubAgentExecutor = new ThreadPoolExecutor(
            3, 3, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
            runnable -> {
                Thread thread = new Thread(runnable,
                        "agent-subtool-" + parallelToolThreadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            // 队列满时由提交线程执行，形成反压并确保已记录的 ToolCall 都能得到闭合结果。
            new ThreadPoolExecutor.CallerRunsPolicy());

    public DefaultAgentRuntime(AgentProperties properties,
                               AgentTimelineStore timelineStore,
                               AgentRunStore runStore,
                               ToolExecutionStore toolExecutionStore,
                               AgentContextManager contextManager,
                               AgentModelGateway modelGateway,
                               AgentCapabilityRegistry capabilityRegistry,
                               AgentToolRuntime toolRuntime,
                               GuardrailService guardrailService,
                               List<AgentFollowUpGuardrailPolicy> followUpGuardrailPolicies,
                               ApprovalService approvalService,
                               TokenEstimator tokenEstimator,
                               AgentRunControlStore runControlStore,
                               MemoryService memoryService,
                               LlmCostCalculator costCalculator,
                               ToolResultProjector toolResultProjector) {
        this.properties = properties;
        this.timelineStore = timelineStore;
        this.runStore = runStore;
        this.toolExecutionStore = toolExecutionStore;
        this.contextManager = contextManager;
        this.modelGateway = modelGateway;
        this.capabilityRegistry = capabilityRegistry;
        this.toolRuntime = toolRuntime;
        this.guardrailService = guardrailService;
        this.followUpGuardrailPolicies = followUpGuardrailPolicies == null
                ? List.of()
                : List.copyOf(followUpGuardrailPolicies);
        this.approvalService = approvalService;
        this.tokenEstimator = tokenEstimator;
        this.runControlStore = runControlStore;
        this.memoryService = memoryService;
        this.costCalculator = costCalculator;
        this.toolResultProjector = toolResultProjector;
    }

    @Override
    public AgentRuntimeResult run(AgentRequest originalRequest, AgentEventListener listener) {
        return run(originalRequest, defaultExecutionProfile(), listener);
    }

    @Override
    public AgentRuntimeResult run(AgentRequest originalRequest,
                                  AgentExecutionProfile executionProfile,
                                  AgentEventListener listener) {
        if (originalRequest == null || originalRequest.question() == null || originalRequest.question().isBlank()) {
            throw new IllegalArgumentException("agent request question must not be blank");
        }
        // Runtime 内部可信的执行配置
        AgentExecutionProfile profile = executionProfile == null
                ? defaultExecutionProfile()
                : executionProfile;
        // 监听器，用于把事件推送到前端
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        // 多轮对话的上下文标识——同一会话所有消息共享
        String sessionId = normalize(originalRequest.conversationId(), DEFAULT_SESSION_ID);
        // 用户身份标识——用于画像、权限、限流
        String userId = normalize(originalRequest.userId(), DEFAULT_USER_ID);
        // 	本次 Agent 执行的唯一标识——串联整条执行链路
        String runId = UUID.randomUUID().toString();
        // 并发租约锁的持有者 ID——防止同一 session 被多个 run 并发执行
        String leaseOwnerId = newLeaseOwnerId(runId);
        // agent 运行预算
        AgentRunBudget budget = new AgentRunBudget(profile.limits());
        // 获取 session 租约，这样就能把请求的结果打印在当前的窗口上
        acquireRun(sessionId, runId, leaseOwnerId, budget);
        boolean runCreated = false;
        try {
            // 开启 session 会话
            timelineStore.openSession(sessionId, userId);
            // 创建 AgentRunRecord
            AgentRunRecord createdRun = AgentRunRecord.create(
                    runId, runId, sessionId, originalRequest, profile, budget.snapshot()
            );
            // runUntilInputCheckpoint 启动时 set(1)，所以这里读到 1 说明"当前 Run 是续跑模式"，它是"被上层编排器调用的子任务"，不会"一次跑到底"，而是"暂停 → 等输入 → 恢复"
            int maxFollowUps = requestedInputCheckpoints.get();
            if (maxFollowUps > 0) {
                // 前置校验
                continuationStore();
                createdRun = createdRun.enableInputCheckpoint(maxFollowUps);
            }
            // 把 agent 的运行记录持久化到数据库
            runStore.create(createdRun);
            runCreated = true;
            // 往数据库中添加 agent 事件并把事件推送前端
            publish(sessionId, userId, runId, AgentEventType.RUN_STARTED,
                    "agent run started", Map.of(
                            "question", originalRequest.question(),
                            "profile", profile.name(),
                            "allowedCapabilities", profile.allowedCapabilities()
                    ), effectiveListener);
            // 输入 Guardrail
            GuardrailDecision inputDecision = guardrailService.checkInput(originalRequest.question());
            if (inputDecision.action() == GuardrailAction.BLOCK) {
                return finish(
                        runId,
                        sessionId,
                        userId,
                        AgentRunState.BLOCKED,
                        AgentStopReason.GUARDRAIL_BLOCKED,
                        "请求被输入安全策略拦截：" + inputDecision.reason(),
                        "",
                        List.of(),
                        List.of(),
                        false,
                        true,
                        budget,
                        effectiveListener
                );
            }
            String safeQuestion = inputDecision.action() == GuardrailAction.REDACT
                    && inputDecision.safeContent() != null
                    ? inputDecision.safeContent()
                    : originalRequest.question();
            // 构建真正的 agent 请求，主要是替换用户问题为安全问题
            AgentRequest request = new AgentRequest(sessionId, userId, safeQuestion, originalRequest.metadata());
            if (!safeQuestion.equals(originalRequest.question())) {
                // 根据新的 AgentRunRecord 更新数据库
                runStore.update(runId, current -> current.withRequest(request));
            }
            // 往数据库中插入消息
            timelineStore.appendMessages(sessionId, userId, runId, List.of(
                    AgentMessageDraft.user(safeQuestion, tokenEstimator.estimate(safeQuestion))
            ));
            if (profile.longTermMemoryEnabled()) {
                // 根据用户问题保存长期记忆和用户画像
                memoryService.rememberLongTerm(sessionId, userId, new MemoryMessage("user", safeQuestion, Instant.now()));
            }
            return executeLoop(request, runId, leaseOwnerId, sessionId, userId, budget,
                    new ArrayList<>(), new ArrayList<>(), false, profile, effectiveListener);
        }
        catch (RuntimeException exception) {
            if (!runCreated) {
                throw exception;
            }
            if (pauseRequested(runId, budget)) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, effectiveListener);
            }
            return finishUnexpectedFailure(
                    runId, sessionId, userId, budget, effectiveListener, exception
            );
        }
        finally {
            releaseRun(sessionId, runId, leaseOwnerId);
        }
    }

    /**
     * agent 执行恢复
     */
    @Override
    public AgentRuntimeResult resume(String runId, AgentEventListener listener) {
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        AgentRunRecord stored = runStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        // 接管崩溃后遗留的 stale Run
        if (stored.state() == AgentRunState.RUNNING) {
            return recoverRunning(stored, effectiveListener);
        }
        // 恢复已请求暂停状态和已暂停状态
        if (stored.state() == AgentRunState.PAUSED || stored.state() == AgentRunState.PAUSE_REQUESTED) {
            return resumePaused(stored, effectiveListener);
        }
        // 不是等待审批状态
        if (stored.state() != AgentRunState.WAITING_APPROVAL) {
            return resultFromStored(stored, inferStoredStopReason(stored));// 不是审批状态，不能恢复
        }
        // ① 找到审批记录
        ApprovalRecord approval = approvalService.find(stored.approvalId())
                .orElseThrow(() -> new IllegalArgumentException("approval not found: " + stored.approvalId()));
        // ② 还在等 → 返回"还在审批中"，就不能把 agent 恢复
        if (approval.status() == ApprovalStatus.REQUESTED) {
            return resultFromStored(stored, AgentStopReason.WAITING_APPROVAL);
        }
        String sessionId = stored.conversationId();
        String leaseOwnerId = newLeaseOwnerId(runId);
        AgentExecutionProfile profile = stored.executionProfile() == null
                ? defaultExecutionProfile()
                : stored.executionProfile();
        AgentRunBudget budget = new AgentRunBudget(profile.limits(), stored.budgetSnapshot());
        boolean acquired = false;
        boolean claimedForResume = false;
        AgentRunRecord claimed = stored;
        try {
            // ③ 审完了 → 从 checkpoint 恢复
            acquireRun(sessionId, runId, leaseOwnerId, budget);// 重新抢租约，防止在同一个 sessio 下有两个不同的 runId
            acquired = true;
            // 更新数据库中的 agent 运行状态和阶段
            Optional<AgentRunRecord> claim = runStore.claimForResume(runId);// 抢执行权，当两个人同时对同一个 runId 点击恢复，只有一个人能把 state=WAITING_APPROVAL → 改为 RUNNING
            if (claim.isEmpty()) {
                AgentRunRecord current = runStore.find(runId).orElse(stored);
                return resultFromStored(current, inferStoredStopReason(current));
            }
            claimed = claim.get();
            claimedForResume = true;
            String userId = normalize(claimed.userId(), DEFAULT_USER_ID);
            List<ToolCallResult> toolResults = new ArrayList<>(claimed.toolResults());
            List<String> usedTools = new ArrayList<>(claimed.usedTools());
            budget.resumeExecution();
            // 查看是否有 agent 的取消请求
            synchronizeCancellation(runId, budget);
            if (budget.pauseRequested()) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, effectiveListener);
            }
            // 在 agent 的执行轮次之前判断是否取消 agent
            Optional<AgentStopReason> resumeStop = budget.beforeTurn();
            if (resumeStop.isPresent()) {
                return finishBudgetStop(claimed.request(), runId, sessionId, userId, resumeStop.get(),
                        toolResults, usedTools, claimed.usedRag(), budget, effectiveListener);
            }

            ToolCallResult result;
            // ④ 审批结果判断，如果是审批拒绝或者是审批过期
            if (approval.status() == ApprovalStatus.REJECTED || approval.status() == ApprovalStatus.EXPIRED) {
                String approvalError = approval.status() == ApprovalStatus.EXPIRED
                        ? "human approval expired"
                        : "human approval rejected: " + approval.decisionReason();
                // 被拒或过期 → 工具结果标记为失败，跳过工具执行
                result = new ToolCallResult(
                        approval.toolCallRequest().toolName(),
                        false,
                        "",
                        approvalError,
                        Map.of("approvalId", approval.approvalId(), "approvalStatus", approval.status().name())
                );
            }
            // ④ 审批结果判断，审批通过，执行工具
            else {
                // 根据工具名称查找工具
                ToolDefinition definition = capabilityRegistry.findCapability(approval.toolCallRequest().toolName())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "approved capability no longer exists: " + approval.toolCallRequest().toolName()
                        ));
                // 执行工具
                AgentToolRuntimeResult execution = toolRuntime.executeApproved(
                        approval,
                        definition,
                        ToolPolicyContext.from(runId, sessionId, userId,
                                claimed.request() == null ? Map.of() : claimed.request().metadata())
                );
                if (execution.status() == AgentToolExecutionStatus.MANUAL_REVIEW) {
                    ToolCallRequest reviewRequest = execution.request() == null
                            ? approval.toolCallRequest()
                            : execution.request();
                    ToolCallResult reviewResult = appendTerminalManualReviewResult(
                            sessionId, userId, runId, reviewRequest, execution.result(),
                            execution.policyReason(), approval.approvalId(), effectiveListener
                    );
                    toolResults.add(reviewResult);
                    usedTools.add(reviewRequest.toolName());
                    budget.recordToolCall();
                    return finish(
                            runId, sessionId, userId, AgentRunState.MANUAL_REVIEW, AgentStopReason.TOOL_ERROR,
                            "工具执行状态不确定，需要人工核对。", approval.approvalId(), toolResults, usedTools,
                            claimed.usedRag(), claimed.blockedByGuardrail(), budget, effectiveListener
                    );
                }
                result = execution.result();
                budget.recordToolCall();
                usedTools.add(execution.request().toolName());
            }
            ToolCallResult projectedResult = appendToolResult(
                    sessionId, userId, runId, approval.toolCallRequest().requestId(), result,
                    approval.status() == ApprovalStatus.APPROVED
            );
            toolResults.add(projectedResult);
            // 记录此刻 agent 的执行阶段，如果接下来发生崩溃，从上下文准备阶段继续
            checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                    toolResults, usedTools, claimed.usedRag(), budget);
            // 往前端发送事件——工具调用已完成
            publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                    result.success() ? "approved tool completed" : "approved tool returned failure",
                    toolResultPayload(approval.toolCallRequest().requestId(), result), effectiveListener);
            AgentRequest request = claimed.request();
            // ⑤ 把结果接回 executeLoop，继续跑
            return executeLoop(request, runId, leaseOwnerId, sessionId, userId, budget, toolResults, usedTools,
                    claimed.usedRag(), profile, effectiveListener);
        }
        catch (AgentSessionBusyException busy) {
            AgentRunRecord current = runStore.find(runId).orElse(stored);
            return resultFromStored(current, AgentStopReason.IN_PROGRESS);
        }
        catch (RuntimeException exception) {
            if (!claimedForResume) {
                throw exception;
            }
            if (pauseRequested(runId, budget)) {
                return pauseAtCheckpoint(
                        runId,
                        sessionId,
                        normalize(claimed.userId(), DEFAULT_USER_ID),
                        budget,
                        effectiveListener
                );
            }
            return finishUnexpectedFailure(
                    runId,
                    sessionId,
                    normalize(claimed.userId(), DEFAULT_USER_ID),
                    budget,
                    effectiveListener,
                    exception
            );
        }
        finally {
            if (acquired) {
                releaseRun(sessionId, runId, leaseOwnerId);
            }
        }
    }

    @Override
    public AgentRuntimeResult runUntilInputCheckpoint(AgentRequest request,
                                                      AgentExecutionProfile profile,
                                                      AgentEventListener listener) {
        // > 0：当前线程已经有一个 input-checkpoint Run 正在执行 → 再启动一个就是嵌套 → 直接抛异常拒绝
        if (requestedInputCheckpoints.get() > 0) {
            throw new IllegalStateException("nested input-checkpoint runs are not supported");
        }
        requestedInputCheckpoints.set(1);
        try {
            return run(request, profile, listener);
        }
        finally {
            requestedInputCheckpoints.remove();
        }
    }

    @Override
    public AgentRuntimeResult continueWithInput(String runId,
                                                AgentFollowUpInput input,
                                                AgentEventListener listener) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (input == null) {
            throw new IllegalArgumentException("follow-up input must not be null");
        }
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        AgentRunRecord stored = runStore.find(runId.trim())
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        if (stored.state() != AgentRunState.WAITING_INPUT) {
            return resultFromStored(stored, inferStoredStopReason(stored));
        }
        validateFollowUpBudget(stored, input);
        GuardrailDecision inputDecision = followUpGuardrailDecision(stored, input)
                .orElseGet(() -> guardrailService.checkInput(input.question()));
        if (inputDecision.action() == GuardrailAction.BLOCK) {
            throw new IllegalArgumentException("follow-up input was blocked by guardrail: " + inputDecision.reason());
        }
        String safeQuestion = inputDecision.action() == GuardrailAction.REDACT
                && inputDecision.safeContent() != null
                ? inputDecision.safeContent()
                : input.question();
        AgentFollowUpInput safeInput = new AgentFollowUpInput(
                input.schemaVersion(), input.followUpType(), input.originalTaskId(), input.conflictId(),
                input.relatedEvidenceIds(), safeQuestion, input.additionalToolBudget(),
                input.additionalTokenBudget(), input.metadata()
        );
        String sessionId = stored.conversationId();
        String userId = normalize(stored.userId(), DEFAULT_USER_ID);
        AgentExecutionProfile profile = stored.executionProfile() == null
                ? defaultExecutionProfile()
                : stored.executionProfile();
        AgentRunBudget budget = new AgentRunBudget(profile.limits(), stored.budgetSnapshot());
        String leaseOwnerId = newLeaseOwnerId(stored.runId());
        boolean acquired = false;
        boolean claimed = false;
        try {
            acquireRun(sessionId, stored.runId(), leaseOwnerId, budget);
            acquired = true;
            LinkedHashMap<String, Object> requestMetadata = new LinkedHashMap<>(
                    stored.request() == null ? Map.of() : stored.request().metadata()
            );
            requestMetadata.put("followUpType", safeInput.followUpType());
            requestMetadata.put("originalTaskId", safeInput.originalTaskId());
            requestMetadata.put("conflictId", safeInput.conflictId());
            requestMetadata.put("relatedEvidenceIds", safeInput.relatedEvidenceIds());
            AgentRequest followUpRequest = new AgentRequest(
                    sessionId, userId, safeQuestion, Map.copyOf(requestMetadata),
                    stored.request() == null ? "" : stored.request().scenarioId()
            );
            int ordinal = stored.followUpCount() + 1;
            AgentMessageDraft message = new AgentMessageDraft(
                    AgentMessageType.USER,
                    safeInput.timelineContent(),
                    "",
                    "",
                    Map.of(),
                    safeInput.timelineMetadata(ordinal),
                    tokenEstimator.estimate(safeInput.timelineContent())
            );
            Optional<AgentContinuationTransition> transition = continuationStore().claimWaitingInput(
                    stored.runId(), stored.version(), followUpRequest, message,
                    new AgentEventDraft(
                            AgentEventType.RUN_INPUT_RECEIVED,
                            "follow-up input claimed for the same run",
                            Map.of(
                                    "followUpOrdinal", ordinal,
                                    "followUpType", safeInput.followUpType(),
                                    "originalTaskId", safeInput.originalTaskId(),
                                    "conflictId", safeInput.conflictId()
                            )
                    )
            );
            if (transition.isEmpty()) {
                AgentRunRecord current = runStore.find(stored.runId()).orElse(stored);
                return resultFromStored(current, inferStoredStopReason(current));
            }
            claimed = true;
            AgentRunRecord current = transition.get().run();
            dispatch(transition.get().event(), effectiveListener);
            budget.resumeExecution();
            synchronizeCancellation(current.runId(), budget);
            if (budget.pauseRequested()) {
                return pauseAtCheckpoint(current.runId(), sessionId, userId, budget, effectiveListener);
            }
            return executeLoop(
                    followUpRequest, current.runId(), leaseOwnerId, sessionId, userId, budget,
                    new ArrayList<>(current.toolResults()), new ArrayList<>(current.usedTools()),
                    current.usedRag(), profile, effectiveListener
            );
        }
        catch (AgentSessionBusyException busy) {
            AgentRunRecord current = runStore.find(stored.runId()).orElse(stored);
            return resultFromStored(current, AgentStopReason.IN_PROGRESS);
        }
        catch (RuntimeException exception) {
            if (!claimed) {
                throw exception;
            }
            return finishUnexpectedFailure(
                    stored.runId(), sessionId, userId, budget, effectiveListener, exception
            );
        }
        finally {
            if (acquired) {
                releaseRun(sessionId, stored.runId(), leaseOwnerId);
            }
        }
    }

    @Override
    public AgentRuntimeResult completeWaitingInput(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        AgentRunRecord stored = runStore.find(runId.trim())
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        if (stored.state() != AgentRunState.WAITING_INPUT) {
            return resultFromStored(stored, inferStoredStopReason(stored));
        }
        Optional<AgentContinuationTransition> completed = continuationStore().completeWaitingInput(
                stored.runId(),
                new AgentEventDraft(
                        AgentEventType.RUN_COMPLETED,
                        stored.answer(),
                        Map.of(
                                "state", AgentRunState.COMPLETED.name(),
                                "stopReason", AgentStopReason.COMPLETED.name(),
                                "completedWithoutFollowUp", true,
                                "budget", budgetPayload(stored.budgetSnapshot())
                        )
                )
        );
        AgentRunRecord result = completed.map(AgentContinuationTransition::run)
                .orElseGet(() -> runStore.find(stored.runId()).orElse(stored));
        return resultFromStored(result, inferStoredStopReason(result));
    }

    @Override
    public boolean cancel(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        String normalizedRunId = runId.trim();
        AgentRunRecord stored = runStore.find(normalizedRunId).orElse(null);
        if (stored == null || isTerminal(stored.state())) {
            return false;
        }
        boolean persisted = runControlStore.requestCancellation(normalizedRunId);
        AgentRunBudget localBudget = activeBudgets.get(normalizedRunId);
        if (localBudget != null) {
            localBudget.cancel();
        }
        // 如果是在数据库中等待人工审批的，直接更新数据库中 agent_run_state 的状态
        else if (persisted && (stored.state() == AgentRunState.WAITING_APPROVAL
                || stored.state() == AgentRunState.WAITING_INPUT
                || stored.state() == AgentRunState.PAUSED
                || stored.state() == AgentRunState.PAUSE_REQUESTED)) {
            AgentExecutionProfile profile = stored.executionProfile() == null
                    ? defaultExecutionProfile()
                    : stored.executionProfile();
            AgentRunBudget cancelledBudget = new AgentRunBudget(profile.limits(), stored.budgetSnapshot());
            cancelledBudget.cancel();
            List<ToolCallResult> cancelledResults = new ArrayList<>(stored.toolResults());
            List<String> cancelledTools = new ArrayList<>(stored.usedTools());
            CancelledPendingToolClosure closure = closePendingToolCallForCancellation(
                    stored,
                    normalize(stored.userId(), DEFAULT_USER_ID)
            );
            if (closure != null) {
                cancelledResults.add(closure.result());
                if (!cancelledTools.contains(closure.result().toolName())) {
                    cancelledTools.add(closure.result().toolName());
                }
                if (closure.executionObserved()) {
                    cancelledBudget.recordToolCall();
                }
            }
            finishBudgetStop(
                    stored.request(), stored.runId(), stored.conversationId(),
                    normalize(stored.userId(), DEFAULT_USER_ID), AgentStopReason.CANCELLED,
                    cancelledResults, cancelledTools, stored.usedRag(), cancelledBudget,
                    AgentEventListener.NOOP
            );
        }
        return persisted;
    }

    @Override
    public boolean pause(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        String normalizedRunId = runId.trim();
        // 查下当前 runId 的 agent 是否有运行记录，有的话就找出来
        AgentRunRecord stored = runStore.find(normalizedRunId).orElse(null);
        if (stored == null || isTerminal(stored.state())) {
            return false;
        }
        // 已暂停 || 已请求暂停
        if (stored.state() == AgentRunState.PAUSED || stored.state() == AgentRunState.PAUSE_REQUESTED) {
            return true;
        }
        // 没有正在运行中
        if (stored.state() != AgentRunState.RUNNING) {
            return false;
        }
        // 当前 agent 请求暂停，数据库持久化暂停标志
        if (!runControlStore.requestPause(normalizedRunId)) {
            return false;
        }
        AgentRunBudget localBudget = activeBudgets.get(normalizedRunId);
        if (localBudget != null) {
            localBudget.requestPause();
            localBudget.pauseExecution();
        }
        AgentRunRecord requested = runStore.update(normalizedRunId, current ->
                current.state() == AgentRunState.RUNNING
                        ? current.pauseRequested(localBudget == null ? current.budgetSnapshot() : localBudget.snapshot())
                        : current
        );
        // 如果是已请求暂停状态，往前端发送事件
        if (requested.state() == AgentRunState.PAUSE_REQUESTED) {
            publish(
                    requested.conversationId(),
                    normalize(requested.userId(), DEFAULT_USER_ID),
                    requested.runId(),
                    AgentEventType.RUN_PAUSE_REQUESTED,// 已请求在安全检查点暂停事件类型
                    "agent run pause requested",
                    Map.of("phase", requested.phase().name()),
                    AgentEventListener.NOOP// 浏览器: 关闭 / 刷新 / 网络断开，此时 SSE 已经断开，所以就不需要往前端推送事件了
            );
            return true;
        }
        return requested.state() == AgentRunState.PAUSED;
    }

    /**
     * executeLoop() 是整个单 Agent 的核心循环，负责反复执行：
     * 准备上下文
     * → 调用 LLM
     * → 判断 LLM 是直接回答还是调用工具
     * → 执行工具
     * → 把工具结果放回上下文
     * → 再次调用 LLM
     * → 直到形成最终回答或被中断
     */
    private AgentRuntimeResult executeLoop(AgentRequest request,
                                           String runId,
                                           String leaseOwnerId,
                                           String sessionId,
                                           String userId,
                                           AgentRunBudget budget,
                                           List<ToolCallResult> toolResults,
                                           List<String> usedTools,
                                           boolean usedRag,
                                           AgentExecutionProfile profile,
                                           AgentEventListener listener) {
        int contextOverflowRetries = 0;
        while (true) {
            // 更新 session 租约（续约），避免 agent 执行时间过长导致租约丢失，然后其他的请求会把当前的 session 写脏
            if (!runControlStore.renewSessionLease(sessionId, leaseOwnerId, sessionLeaseDuration())) {
                return finish(
                        runId, sessionId, userId, AgentRunState.FAILED, AgentStopReason.INTERNAL_ERROR,
                        "会话执行租约已丢失，Runtime 已停止以避免并发写入同一时间线。", "",
                        toolResults, usedTools, usedRag, false, budget, listener
                );
            }

            // 查看是否有 agent 的取消请求，有取消请求的话就不往下执行了
            synchronizeCancellation(runId, budget);
            // ③ 暂停
            if (budget.pauseRequested()) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);// ← 暂停，可恢复
            }
            // 在 agent 的执行轮次之前判断是否取消 agent
            Optional<AgentStopReason> turnStop = budget.beforeTurn();
            if (turnStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, turnStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }
            budget.recordTurnStarted();

            // 把 Agent 执行过程中的"当前快照"持久化到数据库，保证中断后可以恢复，也可以从外部监控当前执行进度
            checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                    toolResults, usedTools, usedRag, budget);

            // 计算上下文消息预算
            long contextTokenBudget = contextMessageBudget(profile);
            String tenantId = ToolPolicyContext.from(
                    runId, sessionId, userId, request.metadata()).tenantId();
            // 从数据库中加载消息 AgentMessage 并做处理
            AgentContextView projectedContext = contextManager.project(
                    sessionId,
                    userId,
                    tenantId,
                    request.question(),
                    contextTokenBudget,
                    profile
            );
            AgentContextView context = projectedContext;
            boolean contextBudgetCompactionRequested = projectedContext.omittedMessages() > 0;
            if (contextBudgetCompactionRequested) {
                context = contextManager.compact(
                        sessionId, userId, tenantId, runId, request.question(), contextTokenBudget,
                        "context_budget", profile
                );
            }
            publish(sessionId, userId, runId,
                    contextBudgetCompactionRequested
                            ? AgentEventType.CONTEXT_COMPACTED : AgentEventType.CONTEXT_PREPARED,
                    contextBudgetCompactionRequested ? "context compacted" : "context projected",
                    contextEventPayload(projectedContext, context,
                            contextBudgetCompactionRequested ? "context_budget" : "projection",
                            contextBudgetCompactionRequested,
                            Map.of("tokenBudget", contextTokenBudget)),
                    listener);
            if (context.estimatedTokens() > contextTokenBudget || context.omittedMessages() > 0) {
                return finish(
                        runId, sessionId, userId, AgentRunState.FAILED, AgentStopReason.CONTEXT_OVERFLOW,
                        "会话上下文无法在保留完整工具调用语义的前提下压缩到模型窗口内。", "",
                        toolResults, usedTools, usedRag, false, budget, listener
                );
            }

            // 查看是否有 agent 的取消请求
            synchronizeCancellation(runId, budget);
            if (budget.pauseRequested()) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
            }
            // 在模型调用之前判断是否取消 agent
            Optional<AgentStopReason> modelStop = budget.beforeModelCall();
            if (modelStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, modelStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }

            // 往前端推送消息
            publish(sessionId, userId, runId, AgentEventType.MODEL_STARTED,
                    "model turn started", Map.of("turn", budget.snapshot().turns()), listener);
            // 保存状态
            checkpoint(runId, AgentRunPhase.MODEL_CALL, null,
                    toolResults, usedTools, usedRag, budget);

            // LLM 调用
            AgentModelTurn modelTurn;
            StreamingModelDeltaPublisher modelDeltaPublisher;
            int modelProtocolRetries = 0;
            while (true) {
                modelDeltaPublisher = new StreamingModelDeltaPublisher(
                        sessionId,
                        userId,
                        runId,
                        budget.snapshot().turns(),
                        listener
                );
                try {
                    // 这是真正的 LLM token 级流式推送
                    modelTurn = modelGateway.nextTurn(new AgentModelRequest(
                            runId,
                            sessionId,
                            profile.systemPrompt(),
                            context.messages(),
                            // 发给 LLM 前过滤，保存 agent 白名单工具和当前阶段可见工具
                            capabilitiesFor(profile, request.metadata(), usedTools),
                            request.metadata()
                    ), modelDeltaPublisher::accept);
                    break;
                }
                catch (RuntimeException modelFailure) {
                    LOGGER.error("Agent model turn failed before Runtime recovery; runId={}, profile={}, turn={}, errorType={}",
                            runId, profile.name(), budget.snapshot().turns(), modelErrorType(modelFailure), modelFailure);
                    // 判断是否是上下文溢出异常
                    boolean contextOverflow = isContextOverflow(modelFailure);
                    String errorType = modelErrorType(modelFailure);
                    boolean protocolFailure = "MODEL_PROTOCOL_ERROR".equals(errorType);
                    publish(sessionId, userId, runId, AgentEventType.MODEL_FAILED,
                            contextOverflow ? "model rejected context" : "model turn failed",
                            Map.of(
                                    "errorType", errorType,
                                    "recoverableByCompaction", contextOverflow,
                                    "recoverableByProtocolRetry", protocolFailure
                            ), listener);
                    if (!contextOverflow) {
                        budget.recordModelCall(new LlmUsage(0, 0, 0, 0, 0, "", "failed"), 0);
                        synchronizeCancellation(runId, budget);
                        if (budget.pauseRequested()) {
                            return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
                        }
                        Optional<AgentStopReason> interruptedStop = budget.currentStopReason();
                        if (interruptedStop.isPresent()) {
                            return finishBudgetStop(request, runId, sessionId, userId, interruptedStop.get(),
                                    toolResults, usedTools, usedRag, budget, listener);
                        }
                    }
                    if (protocolFailure
                            && modelProtocolRetries < Math.max(0, properties.getMaxModelProtocolRetries())) {
                        modelProtocolRetries++;
                        Optional<AgentStopReason> retryStop = budget.beforeModelCall();
                        if (retryStop.isPresent()) {
                            return finishBudgetStop(request, runId, sessionId, userId, retryStop.get(),
                                    toolResults, usedTools, usedRag, budget, listener);
                        }
                        publish(sessionId, userId, runId, AgentEventType.MODEL_STARTED,
                                "model turn retry after protocol rejection",
                                Map.of(
                                        "turn", budget.snapshot().turns(),
                                        "protocolRetry", modelProtocolRetries,
                                        "maxRetries", properties.getMaxModelProtocolRetries()
                                ), listener);
                        continue;
                    }
                    // ============ 上下文溢出处理 ============
                    if (contextOverflow
                            && contextOverflowRetries < Math.max(0, properties.getMaxContextOverflowRetries())) {
                        contextOverflowRetries++;
                        budget.recordModelCall(new LlmUsage(0, 0, 0, 0, 0, "", "context-overflow"), 0);
                        Optional<AgentStopReason> retryStop = budget.beforeModelCall();
                        if (retryStop.isPresent()) {
                            return finishBudgetStop(request, runId, sessionId, userId, retryStop.get(),
                                    toolResults, usedTools, usedRag, budget, listener);
                        }
                        // ① 压缩上下文，预算减半
                        long retryBudget = Math.max(1, contextTokenBudget / 2);
                        AgentContextView beforeOverflowCompaction = context;
                        context = contextManager.compact(
                                sessionId, userId, tenantId, runId, request.question(), retryBudget,
                                "provider_context_overflow", profile
                        );
                        publish(sessionId, userId, runId, AgentEventType.CONTEXT_COMPACTED,
                                "provider rejected context; compacted before bounded retry",
                                contextEventPayload(beforeOverflowCompaction, context,
                                        "provider_context_overflow", true,
                                        Map.of(
                                                "retry", contextOverflowRetries,
                                                "maxRetries", properties.getMaxContextOverflowRetries(),
                                                "tokenBudget", contextTokenBudget,
                                                "retryBudget", retryBudget
                                        )), listener);
                        // ② 压缩后还是超 → 直接放弃
                        if (context.estimatedTokens() > retryBudget || context.omittedMessages() > 0) {
                            return finish(
                                    runId, sessionId, userId, AgentRunState.FAILED,
                                    AgentStopReason.CONTEXT_OVERFLOW,
                                    "模型上下文溢出，压缩后仍无法安全放入上下文窗口。", "",
                                    toolResults, usedTools, usedRag, false, budget, listener
                            );
                        }
                        publish(sessionId, userId, runId, AgentEventType.MODEL_STARTED,
                                "model turn retry after context compaction",
                                Map.of("turn", budget.snapshot().turns(), "contextOverflowRetry", contextOverflowRetries),
                                listener);
                        continue;
                    }
                    // 重试次数用完 → 放弃
                    if (contextOverflow) {
                        budget.recordModelCall(new LlmUsage(0, 0, 0, 0, 0, "", "context-overflow"), 0);
                    }
                    AgentStopReason stopReason = contextOverflow
                            ? AgentStopReason.CONTEXT_OVERFLOW
                            : AgentStopReason.MODEL_ERROR;
                    String answer = stopReason == AgentStopReason.CONTEXT_OVERFLOW
                            ? "模型上下文仍超过窗口限制，已停止本次运行以避免无限重试。"
                            : "模型调用失败，请稍后重试。";
                    return finish(
                            runId, sessionId, userId, AgentRunState.FAILED, stopReason,
                            answer, "", toolResults, usedTools, usedRag, false, budget, listener
                    );
                }
            }

            //  LLM 调用了多少 token
            LlmUsage effectiveUsage = effectiveUsage(modelTurn, context);
            //  LLM 调用花了多少钱
            double modelCallCost = costCalculator.estimate(effectiveUsage);
            budget.recordModelCall(effectiveUsage, modelCallCost);

            // 保存状态
            checkpoint(runId, AgentRunPhase.MODEL_CALL, null,
                    toolResults, usedTools, usedRag, budget);
            // 往前端推送消息
            publish(sessionId, userId, runId, AgentEventType.MODEL_COMPLETED,
                    "model turn completed",
                    Map.of(
                            "finishReason", modelTurn.finishReason(),
                            "toolCallCount", modelTurn.toolCalls().size(),
                            "promptTokens", effectiveUsage.promptTokens(),
                            "completionTokens", effectiveUsage.completionTokens(),
                            "usageSource", effectiveUsage.source(),
                            "estimatedCost", modelCallCost
                    ),
                    listener);

            // 查看是否有 agent 的取消请求
            synchronizeCancellation(runId, budget);
            if (budget.pauseRequested()) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
            }
            Optional<AgentStopReason> asynchronousStop = budget.currentStopReason();
            if (asynchronousStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, asynchronousStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }

            // ② 正常完成（LLM 不再要调工具）
            // 本次 LLM 调用没有返回工具调用，直接对本地 LLM 答案做护栏输出并添加 LLM 返回消息，然后返回结果
            if (!modelTurn.hasToolCalls()) {
                return finishFinalAnswer(request, runId, sessionId, userId, modelTurn.assistantText(),
                        toolResults, usedTools, usedRag, budget, listener, modelDeltaPublisher);
            }
            // 添加 LLM 返回消息
            if (!modelTurn.assistantText().isBlank()) {
                timelineStore.appendMessages(sessionId, userId, runId, List.of(
                        new AgentMessageDraft(
                                AgentMessageType.ASSISTANT_TEXT,
                                modelTurn.assistantText(),
                                "",
                                "",
                                Map.of(),
                                Map.of("intermediate", true),
                                tokenEstimator.estimate(modelTurn.assistantText())
                        )
                ));
            }

            // 运行 SubAgent Tool
            if (parallelSafeSubAgentBatch(
                    modelTurn.toolCalls(), profile, request.metadata(), usedTools)) {
                AgentRuntimeResult terminal = executeParallelSubAgentBatch(
                        request, runId, sessionId, userId, profile, modelTurn.toolCalls(),
                        modelTurn.reasoningContent(),
                        toolResults, usedTools, usedRag, budget, listener);
                if (terminal != null) {
                    return terminal;
                }
                continue;
            }

            // 普通工具调用
            for (AgentToolCall rawCall : modelTurn.toolCalls()) {
                // 判断 agent 是否暂停
                synchronizeCancellation(runId, budget);
                if (budget.pauseRequested()) {
                    return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
                }
                Optional<AgentStopReason> toolStop = budget.beforeToolCall();
                if (toolStop.isPresent()) {
                    return finishBudgetStop(request, runId, sessionId, userId, toolStop.get(),
                            toolResults, usedTools, usedRag, budget, listener);
                }

                String modelToolCallId = rawCall.toolCallId();       // DeepSeek 返回的原始 ID，如 "call_abc123"
                AgentToolCall call = assignExecutionId(rawCall);     // 项目自己的 UUID，如 "f3a8-4b12-..."
                ToolCallRequest checkpointCall = new ToolCallRequest(
                        call.toolName(), call.toolCallId(), call.arguments()
                );
                checkpoint(runId, AgentRunPhase.EXECUTING_TOOL, checkpointCall,
                        toolResults, usedTools, usedRag, budget);
                // 添加工具调用消息
                timelineStore.appendMessages(sessionId, userId, runId, List.of(
                        AgentMessageDraft.toolCall(
                                call.toolCallId(),
                                call.toolName(),
                                call.arguments(),
                                providerToolCallMetadata(call, modelToolCallId, modelTurn.reasoningContent()),
                                tokenEstimator.estimate(String.valueOf(call.arguments()))
                        )
                ));
                publish(sessionId, userId, runId, AgentEventType.TOOL_REQUESTED,
                        "model requested capability",
                        Map.of(
                                "toolCallId", call.toolCallId(),
                                "modelToolCallId", modelToolCallId,
                                "toolName", call.toolName(),
                                "arguments", call.arguments()
                        ),
                        listener);

                // 工具执行前的校验
                // 工具是否在 Profile 白名单里
                boolean capabilityAllowed = profile.allows(call.toolName());
                // 工具是否注册过（是已知能力，还是模型编的）
                Optional<ToolDefinition> registeredDefinition = capabilityAllowed
                        ? capabilityRegistry.findCapability(call.toolName())// 根据工具名称查询执行的工具
                        : Optional.empty();
                // 工具是否在当前阶段可见（阶段门禁）
                boolean phaseVisible = registeredDefinition
                        .map(item -> AgentCapabilityVisibilityPolicy.visible(item, request.metadata()))
                        .orElse(false);
                Optional<ToolDefinition> definition = phaseVisible
                        ? registeredDefinition
                        : Optional.empty();

                // 精确分类三种失败原因（fail-fast 定位）
                if (definition.isEmpty()) {
                    String errorType = !capabilityAllowed
                            ? "CAPABILITY_NOT_ALLOWED"// 白名单外（越权）
                            : registeredDefinition.isEmpty()
                            ? "UNKNOWN_CAPABILITY" // 不存在（幻觉）
                            : "CAPABILITY_NOT_AVAILABLE_IN_PHASE"; // 阶段不可见
                    String errorMessage = !capabilityAllowed
                            ? "capability is not allowed by execution profile: " + call.toolName()
                            : registeredDefinition.isEmpty()
                            ? "unknown capability: " + call.toolName()
                            : "capability is not available in the current Agent phase: " + call.toolName();
                    ToolCallResult unknown = new ToolCallResult(
                            call.toolName(), false, "", errorMessage,
                            Map.of("errorType", errorType, "profile", profile.name())
                    );
                    // 把失败结果追加回时间线
                    ToolCallResult projectedUnknown = appendToolResult(
                            sessionId, userId, runId, call.toolCallId(), unknown, false
                    );
                    // 加入结果列表
                    toolResults.add(projectedUnknown);
                    // 记录工具调用次数
                    budget.recordToolCall();
                    // 持久化检查点
                    checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                            toolResults, usedTools, usedRag, budget);
                    // 发布事件
                    publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                            "unknown capability returned to model", toolResultPayload(call.toolCallId(), unknown), listener);
                    // 继续处理下一个工具调用
                    continue;
                }

                // singleUse 重复检查，singleUse 工具同一 Run 只能用一次——专家工具
                if (Boolean.TRUE.equals(definition.get().metadata().get("singleUse"))
                        && usedTools.contains(call.toolName())) {
                    // singleUse 工具本 Run 已经用过 → 拒绝
                    ToolCallResult duplicate = new ToolCallResult(
                            call.toolName(), false, "",
                            "single-use capability has already been called in this parent run",
                            Map.of(
                                    "errorType", "SINGLE_USE_CAPABILITY_REPEATED",
                                    "retryable", false,
                                    "profile", profile.name()));
                    ToolCallResult projectedDuplicate = appendToolResult(
                            sessionId, userId, runId, call.toolCallId(), duplicate, false);
                    toolResults.add(projectedDuplicate);
                    budget.recordToolCall();
                    checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                            toolResults, usedTools, usedRag, budget);
                    publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                            "repeated single-use capability rejected",
                            toolResultPayload(call.toolCallId(), duplicate), listener);
                    continue;
                }

                // 工具调用
                AgentToolRuntimeResult execution = toolRuntime.execute(
                        runId,
                        sessionId,
                        userId,
                        request.metadata(),
                        call,
                        definition.get()
                );
                publish(sessionId, userId, runId, AgentEventType.POLICY_DECIDED,
                        execution.policyReason(),
                        Map.of(
                                "toolCallId", call.toolCallId(),
                                "toolName", call.toolName(),
                                "action", execution.policyAction() == null ? "UNKNOWN" : execution.policyAction().name()
                        ),
                        listener);

                // 工具处于等待批准状态
                if (execution.status() == AgentToolExecutionStatus.WAITING_APPROVAL) {
                    // ① 冻结 deadline（暂停计时）
                    budget.pauseExecution();
                    List<ToolCallResult> persistedToolResults = List.copyOf(toolResults);
                    List<String> persistedUsedTools = List.copyOf(usedTools);
                    boolean ragUsedAtPause = usedRag;
                    // ② 把完整状态写入数据库：审批 ID、待执行工具、已执行工具结果、预算快照
                    runStore.update(runId, current -> current.waitingForApproval(
                            execution.approvalId(),
                            execution.request(),// 待审批的工具
                            persistedToolResults,// 已执行的工具结果
                            persistedUsedTools,
                            ragUsedAtPause,
                            budget.snapshot()// 预算快照
                    ));
                    // ③ 通知前端（SSE 事件）
                    publish(sessionId, userId, runId, AgentEventType.APPROVAL_REQUIRED,
                            "tool call is waiting for human approval",
                            Map.of(
                                    "approvalId", execution.approvalId(),
                                    "toolCallId", call.toolCallId(),
                                    "toolName", call.toolName()
                            ),
                            listener);
                    // ④ 返回 → 释放租约 → 线程释放
                    return new AgentRuntimeResult(
                            runId,
                            sessionId,
                            AgentRunState.WAITING_APPROVAL,
                            AgentStopReason.WAITING_APPROVAL,
                            "等待人工审批",
                            execution.approvalId(),
                            budget.snapshot(),
                            timelineStore.loadEvents(runId, MAX_RETURNED_EVENTS)
                    );
                }

                // 工具处于人工审核状态
                if (execution.status() == AgentToolExecutionStatus.MANUAL_REVIEW) {
                    ToolCallRequest reviewRequest = execution.request() == null
                            ? new ToolCallRequest(call.toolName(), call.toolCallId(), call.arguments())
                            : execution.request();
                    ToolCallResult reviewResult = appendTerminalManualReviewResult(
                            sessionId, userId, runId, reviewRequest, execution.result(),
                            execution.policyReason(), "", listener
                    );
                    toolResults.add(reviewResult);
                    usedTools.add(reviewRequest.toolName());
                    budget.recordToolCall();
                    return finish(
                            runId, sessionId, userId, AgentRunState.MANUAL_REVIEW, AgentStopReason.TOOL_ERROR,
                            "工具执行状态不确定，需要人工核对后再继续。", "", toolResults, usedTools,
                            usedRag, false, budget, listener
                    );
                }

                budget.recordToolCall();
                ToolCallResult toolResult = execution.result();
                // 把工具调用结果存入数据库
                ToolCallResult projectedToolResult = appendToolResult(
                        sessionId, userId, runId, call.toolCallId(), toolResult,
                        execution.status() == AgentToolExecutionStatus.COMPLETED
                                || execution.status() == AgentToolExecutionStatus.FAILED
                );
                toolResults.add(projectedToolResult);
                usedTools.add(call.toolName());
                if (DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH.equals(call.toolName()) && toolResult.success()) {
                    usedRag = true;
                }
                publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                        toolResult.success() ? "capability completed" : "capability failure returned to model",
                        toolResultPayload(call.toolCallId(), toolResult), listener);
                checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                        toolResults, usedTools, usedRag, budget);
            }
        }
    }

    /**
     * 判断这一批工具调用是否满足并行执行的所有安全条件
     */
    private boolean parallelSafeSubAgentBatch(List<AgentToolCall> rawCalls,
                                               AgentExecutionProfile profile,
                                               Map<String, Object> requestMetadata,
                                               List<String> usedTools) {
        // 子代理数量 2~3
        if (rawCalls == null || rawCalls.size() < 2 || rawCalls.size() > 3) {
            return false;
        }
        Set<String> names = new java.util.HashSet<>();
        for (AgentToolCall call : rawCalls) {
            // 名称不重复、profile 允许
            if (call == null || !profile.allows(call.toolName()) || !names.add(call.toolName())) {
                return false;
            }
            ToolDefinition definition = capabilityRegistry.findCapability(call.toolName()).orElse(null);
            if (definition == null
                    || !AgentCapabilityVisibilityPolicy.visible(definition, requestMetadata)
                    || definition.riskLevel() != com.agent.platform.tool.ToolRiskLevel.LOW // 低风险
                    || !Boolean.TRUE.equals(definition.metadata().get("readOnly")) // 只读
                    || !Boolean.TRUE.equals(definition.metadata().get("parallelSafe")) // 可并行
                    || !"SUB_AGENT".equals(definition.metadata().get("executionKind")) // 执行类型是 SUB_AGENT
                    || (Boolean.TRUE.equals(definition.metadata().get("singleUse")) // 满足单次使用约束
                    && usedTools.contains(call.toolName()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 仅并行执行显式声明为只读、低风险且可并行的 SubAgent Tool。
     * ToolExecutionStore 和领域 Task 幂等仍然是最终执行边界；普通工具继续走下方串行路径。
     */
    private AgentRuntimeResult executeParallelSubAgentBatch(AgentRequest request,
                                                            String runId,
                                                            String sessionId,
                                                            String userId,
                                                            AgentExecutionProfile profile,
                                                            List<AgentToolCall> rawCalls,
                                                            String reasoningContent,
                                                            List<ToolCallResult> toolResults,
                                                            List<String> usedTools,
                                                            boolean usedRag,
                                                            AgentRunBudget budget,
                                                            AgentEventListener listener) {
        // 预算检查，超预算直接终止
        Optional<AgentStopReason> toolStop = budget.beforeToolCalls(rawCalls.size());
        if (toolStop.isPresent()) {
            return finishBudgetStop(request, runId, sessionId, userId, toolStop.get(),
                    toolResults, usedTools, usedRag, budget, listener);
        }

        List<ParallelSubAgentCall> calls = new ArrayList<>();
        for (AgentToolCall rawCall : rawCalls) {
            String modelToolCallId = rawCall.toolCallId();
            AgentToolCall call = assignExecutionId(rawCall);// 分配执行 ID
            ToolDefinition definition = capabilityRegistry.findCapability(call.toolName()).orElseThrow();
            ToolCallRequest checkpointCall = new ToolCallRequest(
                    call.toolName(), call.toolCallId(), call.arguments());
            // 持久化检查点
            checkpoint(runId, AgentRunPhase.EXECUTING_TOOL, checkpointCall,
                    toolResults, usedTools, usedRag, budget);
            // 追加时间线消息
            timelineStore.appendMessages(sessionId, userId, runId, List.of(
                    AgentMessageDraft.toolCall(
                            call.toolCallId(), call.toolName(), call.arguments(),
                            providerToolCallMetadata(call, modelToolCallId, reasoningContent),
                            tokenEstimator.estimate(String.valueOf(call.arguments())))));
            // 推送事件
            publish(sessionId, userId, runId, AgentEventType.TOOL_REQUESTED,
                    "model requested parallel-safe sub-agent capability",
                    Map.of(
                            "toolCallId", call.toolCallId(),
                            "modelToolCallId", modelToolCallId,
                            "toolName", call.toolName(),
                            "arguments", call.arguments(),
                            "parallelBatch", true),
                    listener);
            calls.add(new ParallelSubAgentCall(call, definition));
        }

        // 并行执行（核心）
        List<CompletableFuture<ParallelSubAgentExecution>> futures = calls.stream()
                .map(item -> CompletableFuture.supplyAsync(
                        () -> executeParallelCall(request, runId, sessionId, userId, item),
                        parallelSubAgentExecutor))
                .toList();
        // 等待全部完成
        List<ParallelSubAgentExecution> executions = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        List<ParallelSubAgentExecution> waitingApproval = new ArrayList<>();
        boolean manualReview = false;
        for (ParallelSubAgentExecution item : executions) {
            AgentToolCall call = item.call();
            AgentToolRuntimeResult execution = item.execution();
            publish(sessionId, userId, runId, AgentEventType.POLICY_DECIDED,
                    execution.policyReason(),
                    Map.of(
                            "toolCallId", call.toolCallId(),
                            "toolName", call.toolName(),
                            "action", execution.policyAction() == null
                                    ? "UNKNOWN" : execution.policyAction().name(),
                            "parallelBatch", true),
                    listener);
            if (execution.status() == AgentToolExecutionStatus.WAITING_APPROVAL) {
                waitingApproval.add(item);
                continue;
            }
            budget.recordToolCall();
            if (execution.status() == AgentToolExecutionStatus.MANUAL_REVIEW) {
                ToolCallRequest reviewRequest = execution.request() == null
                        ? new ToolCallRequest(call.toolName(), call.toolCallId(), call.arguments())
                        : execution.request();
                ToolCallResult reviewResult = appendTerminalManualReviewResult(
                        sessionId, userId, runId, reviewRequest, execution.result(),
                        execution.policyReason(), "", listener);
                toolResults.add(reviewResult);
                usedTools.add(call.toolName());
                manualReview = true;
                continue;
            }

            ToolCallResult result = execution.result() == null
                    ? parallelFailure(call, "parallel sub-agent returned no result")
                    : execution.result();
            ToolCallResult projected = appendToolResult(
                    sessionId, userId, runId, call.toolCallId(), result,
                    execution.status() == AgentToolExecutionStatus.COMPLETED
                            || execution.status() == AgentToolExecutionStatus.FAILED);
            toolResults.add(projected);
            usedTools.add(call.toolName());
            publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                    result.success() ? "parallel sub-agent completed"
                            : "parallel sub-agent failure returned to model",
                    toolResultPayload(call.toolCallId(), result), listener);
        }

        if (manualReview || waitingApproval.size() > 1) {
            for (ParallelSubAgentExecution waiting : waitingApproval) {
                budget.recordToolCall();
                ToolCallRequest pending = waiting.execution().request();
                ToolCallResult closed = appendTerminalManualReviewResult(
                        sessionId, userId, runId, pending, null,
                        "multiple outcomes in one parallel batch require manual review",
                        waiting.execution().approvalId(), listener);
                toolResults.add(closed);
                usedTools.add(waiting.call().toolName());
            }
            checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                    toolResults, usedTools, usedRag, budget);
            return finish(
                    runId, sessionId, userId, AgentRunState.MANUAL_REVIEW, AgentStopReason.TOOL_ERROR,
                    "并行子 Agent 执行出现不确定结果，需要人工核对。", "", toolResults, usedTools,
                    usedRag, false, budget, listener);
        }

        if (waitingApproval.size() == 1) {
            ParallelSubAgentExecution waiting = waitingApproval.get(0);
            AgentToolRuntimeResult execution = waiting.execution();
            budget.pauseExecution();
            runStore.update(runId, current -> current.waitingForApproval(
                    execution.approvalId(), execution.request(), List.copyOf(toolResults),
                    List.copyOf(usedTools), usedRag, budget.snapshot()));
            publish(sessionId, userId, runId, AgentEventType.APPROVAL_REQUIRED,
                    "parallel sub-agent tool call is waiting for human approval",
                    Map.of(
                            "approvalId", execution.approvalId(),
                            "toolCallId", waiting.call().toolCallId(),
                            "toolName", waiting.call().toolName(),
                            "parallelBatch", true),
                    listener);
            return new AgentRuntimeResult(
                    runId, sessionId, AgentRunState.WAITING_APPROVAL,
                    AgentStopReason.WAITING_APPROVAL, "等待人工审批", execution.approvalId(),
                    budget.snapshot(), timelineStore.loadEvents(runId, MAX_RETURNED_EVENTS));
        }

        checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                toolResults, usedTools, usedRag, budget);
        return null;
    }

    private ParallelSubAgentExecution executeParallelCall(AgentRequest request,
                                                          String runId,
                                                          String sessionId,
                                                          String userId,
                                                          ParallelSubAgentCall item) {
        try {
            return new ParallelSubAgentExecution(
                    item.call(),
                    toolRuntime.execute(
                            runId, sessionId, userId, request.metadata(), item.call(), item.definition()));
        }
        catch (RuntimeException exception) {
            ToolCallRequest failedRequest = new ToolCallRequest(
                    item.call().toolName(), item.call().toolCallId(), item.call().arguments());
            ToolCallResult failure = parallelFailure(
                    item.call(), "parallel sub-agent execution failed: " + exception.getClass().getSimpleName());
            return new ParallelSubAgentExecution(
                    item.call(),
                    new AgentToolRuntimeResult(
                            AgentToolExecutionStatus.FAILED, failedRequest, failure,
                            GuardrailAction.ALLOW, "parallel execution boundary caught failure", "", false));
        }
    }

    private ToolCallResult parallelFailure(AgentToolCall call, String message) {
        return new ToolCallResult(
                call.toolName(), false, "", message,
                Map.of("retryable", true, "executionKind", "SUB_AGENT", "parallelBatch", true));
    }

    private record ParallelSubAgentCall(AgentToolCall call, ToolDefinition definition) { }

    private record ParallelSubAgentExecution(AgentToolCall call, AgentToolRuntimeResult execution) { }

    /**
     * 把 agent 从暂停状态恢复为运行状态
     */
    private AgentRuntimeResult resumePaused(AgentRunRecord stored, AgentEventListener listener) {
        String runId = stored.runId();
        String sessionId = stored.conversationId();
        String userId = normalize(stored.userId(), DEFAULT_USER_ID);
        String leaseOwnerId = newLeaseOwnerId(runId);
        AgentExecutionProfile profile = stored.executionProfile() == null
                ? defaultExecutionProfile()
                : stored.executionProfile();
        AgentRunBudget budget = new AgentRunBudget(profile.limits(), stored.budgetSnapshot());
        boolean acquired = false;
        try {
            // 获取 session 租约
            acquireRun(sessionId, runId, leaseOwnerId, budget);
            acquired = true;
            // 更新数据库中的 agent 运行状态，只恢复已请求暂停状态和已暂停状态的 agent，把 agent 从暂停状态恢复为运行状态并持久化到数据库
            Optional<AgentRunRecord> claim = runStore.claimPausedForResume(runId);
            // 如果 agent 不是已请求暂停状态和已暂停状态，返回空
            if (claim.isEmpty()) {
                // 查一遍最新状态
                AgentRunRecord current = runStore.find(runId).orElse(stored);
                return resultFromStored(current, inferStoredStopReason(current));
            }
            AgentRunRecord claimed = claim.get();
            // 清理当前 agent runId 的暂停请求
            if (!runControlStore.clearPauseRequest(runId)) {
                throw new IllegalStateException("failed to clear pause request for run: " + runId);
            }
            // 清理暂停请求
            budget.clearPauseRequest();
            budget.resumeExecution();// 恢复 agent 执行
            runStore.update(runId, current -> current.withBudgetSnapshot(budget.snapshot()));
            // 往前端推送运行已恢复时间
            publish(sessionId, userId, runId, AgentEventType.RUN_RESUMED,
                    "user-paused checkpoint resumed", Map.of(
                            "phase", claimed.phase().name(),
                            "resumeCount", claimed.resumeCount(),
                            "resumeSource", "USER_PAUSE"
                    ), listener);
            if (claimed.phase() == AgentRunPhase.EXECUTING_TOOL) {
                return recoverExecutingTool(
                        claimed, leaseOwnerId, sessionId, userId, budget, profile, listener
                );
            }
            return executeLoop(
                    claimed.request(), runId, leaseOwnerId, sessionId, userId, budget,
                    new ArrayList<>(claimed.toolResults()), new ArrayList<>(claimed.usedTools()),
                    claimed.usedRag(), profile, listener
            );
        }
        catch (AgentSessionBusyException busy) {
            AgentRunRecord current = runStore.find(runId).orElse(stored);
            return resultFromStored(current, AgentStopReason.IN_PROGRESS);
        }
        catch (RuntimeException failure) {
            if (pauseRequested(runId, budget)) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
            }
            return finishUnexpectedFailure(runId, sessionId, userId, budget, listener, failure);
        }
        finally {
            if (acquired) {
                releaseRun(sessionId, runId, leaseOwnerId);
            }
        }
    }

    /**
     * 恢复可能已经死掉的 run agent
     * 如果当前 runId 的 agent 没有死掉，那么当前方法就获取不到租约就会抛异常失败
     */
    private AgentRuntimeResult recoverRunning(AgentRunRecord stored, AgentEventListener listener) {
        String runId = stored.runId();
        String sessionId = stored.conversationId();
        String userId = normalize(stored.userId(), DEFAULT_USER_ID);
        String leaseOwnerId = newLeaseOwnerId(runId);
        AgentExecutionProfile profile = stored.executionProfile() == null
                ? defaultExecutionProfile()
                : stored.executionProfile();
        AgentRunBudget budget = new AgentRunBudget(profile.limits(), stored.budgetSnapshot());
        boolean acquired = false;
        try {
            // ① 抢租约（死掉的 run 租约应该已过期）
            acquireRun(sessionId, runId, leaseOwnerId, budget);
            acquired = true;
            // ② 再次确认状态（抢租约期间可能被其他实例恢复了）
            AgentRunRecord current = runStore.find(runId).orElse(stored);
            if (current.state() != AgentRunState.RUNNING) {
                return resultFromStored(current, inferStoredStopReason(current));
            }
            // ③ 标记为“已被本实例接管”
            AgentRunRecord claimed = runStore.update(runId, AgentRunRecord::claimedForRecovery);
            if (pauseRequested(runId, budget)) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
            }
            publish(sessionId, userId, runId, AgentEventType.RUN_RESUMED,
                    "stale running checkpoint recovered", Map.of(
                            "phase", claimed.phase().name(),
                            "resumeCount", claimed.resumeCount()
                    ), listener);
            // ④ 看上一个 checkpoint 卡在哪个阶段
            if (claimed.phase() == AgentRunPhase.EXECUTING_TOOL) {
                // 正在执行工具时崩了 → 从工具恢复
                return recoverExecutingTool(
                        claimed, leaseOwnerId, sessionId, userId, budget, profile, listener
                );
            }
            // 从 executeLoop 继续（预算、工具结果都从 checkpoint 还原）
            return executeLoop(
                    claimed.request(), runId, leaseOwnerId, sessionId, userId, budget,
                    new ArrayList<>(claimed.toolResults()), new ArrayList<>(claimed.usedTools()),
                    claimed.usedRag(), profile, listener
            );
        }
        catch (AgentSessionBusyException busy) {
            return resultFromStored(stored, AgentStopReason.IN_PROGRESS);
        }
        catch (RuntimeException failure) {
            if (pauseRequested(runId, budget)) {
                return pauseAtCheckpoint(runId, sessionId, userId, budget, listener);
            }
            return finishUnexpectedFailure(runId, sessionId, userId, budget, listener, failure);
        }
        finally {
            if (acquired) {
                releaseRun(sessionId, runId, leaseOwnerId);
            }
        }
    }

    /**
     * 从 checkpoint 恢复执行的工具，并把结果交给 executeLoop 继续执行
     */
    private AgentRuntimeResult recoverExecutingTool(AgentRunRecord claimed,
                                                     String leaseOwnerId,
                                                     String sessionId,
                                                     String userId,
                                                     AgentRunBudget budget,
                                                     AgentExecutionProfile profile,
                                                     AgentEventListener listener) {
        // ① 从 checkpoint 取出"卡住的那个工具"
        ToolCallRequest pending = claimed.pendingToolCall();
        // ② 查工具执行表——工具到底执行了没有
        ToolExecutionRecord execution = pending == null
                ? null
                : toolExecutionStore.findToolExecution(pending.requestId()).orElse(null);
        if (!hasCertainPersistedResult(claimed.runId(), pending, execution)) {
            execution = toolRuntime.reconcileUncertain(execution);
        }
        // ③ 结果不确定 → 进人工审核
        if (!hasCertainPersistedResult(claimed.runId(), pending, execution)) {
            String executionState = execution == null ? "UNKNOWN" : execution.state().name();
            List<ToolCallResult> reviewResults = new ArrayList<>(claimed.toolResults());
            List<String> reviewTools = new ArrayList<>(claimed.usedTools());
            if (pending != null) {
                String reviewReason = execution == null || execution.errorMessage().isBlank()
                        ? "persisted tool state remains uncertain: " + executionState
                        : execution.errorMessage();
                ToolCallResult reviewResult = appendTerminalManualReviewResult(
                        sessionId, userId, claimed.runId(), pending,
                        execution == null ? null : execution.result(), reviewReason, "", listener
                );
                reviewResults.add(reviewResult);
                reviewTools.add(pending.toolName());
                budget.recordToolCall();
            }
            return finish(
                    claimed.runId(), sessionId, userId, AgentRunState.MANUAL_REVIEW, AgentStopReason.TOOL_ERROR,
                    "工具执行检查点结果不确定，需要人工核对：" + executionState,
                    claimed.approvalId(), reviewResults, reviewTools, claimed.usedRag(),
                    claimed.blockedByGuardrail(), budget, listener
            );
        }
        // ④ 结果确定 → 接起来继续
        // 拿到工具执行结果（工具执行失败也有一个错误的结果）
        ToolCallResult rawResult = execution.result();
        // 把原始工具结果"裁剪"成安全、有界的版本再发给 LLM
        ToolCallResult projectedResult = toolResultProjector.project(pending.requestId(), rawResult, true);
        // 如果时间线里还没有这个工具结果，补写进去
        if (!timelineContainsToolResult(sessionId, claimed.runId(), pending.requestId())) {
            projectedResult = appendToolResult(
                    sessionId, userId, claimed.runId(), pending.requestId(), rawResult, true
            );
        }
        // 恢复状态：工具结果、已用工具列表、预算
        List<ToolCallResult> toolResults = new ArrayList<>(claimed.toolResults());
        toolResults.add(projectedResult);
        List<String> usedTools = new ArrayList<>(claimed.usedTools());
        usedTools.add(pending.toolName());
        boolean usedRag = claimed.usedRag()
                || (DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH.equals(pending.toolName()) && rawResult.success());
        // 扣除一次工具调用预算
        budget.recordToolCall();
        // 写入 checkpoint：标志工具执行阶段结束，回到 CONTEXT_PREPARATION
        checkpoint(claimed.runId(), AgentRunPhase.CONTEXT_PREPARATION, null,
                toolResults, usedTools, usedRag, budget);

        Map<String, Object> eventPayload = new LinkedHashMap<>(
                toolResultPayload(pending.requestId(), rawResult)
        );
        eventPayload.put("recoveredFromToolExecutionStore", true);
        eventPayload.put("persistedExecutionState", execution.state().name());
        publish(sessionId, userId, claimed.runId(), AgentEventType.TOOL_COMPLETED,
                rawResult.success() ? "persisted tool success recovered" : "persisted tool failure recovered",
                Map.copyOf(eventPayload), listener);
        // ⑤ 继续跑 executeLoop
        return executeLoop(
                claimed.request(), claimed.runId(), leaseOwnerId, sessionId, userId, budget,
                toolResults, usedTools, usedRag, profile, listener
        );
    }

    /**
     * 恢复时判断"工具到底执行了没有"——执行了就跑下一步，不确定就进人工审核，绝不能盲猜。
     */
    private boolean hasCertainPersistedResult(String runId,
                                              ToolCallRequest pending,
                                              ToolExecutionRecord execution) {
        // ① 任意为空 → 不确定
        if (pending == null || execution == null || execution.result() == null) {
            return false;
        }
        // ② 不属于同一个 run / 不匹配同一个工具 → 不确定
        if (!runId.equals(execution.runId())
                || !pending.requestId().equals(execution.toolCallId())
                || !pending.toolName().equals(execution.toolName())) {
            return false;
        }
        // ③ 结果是"确定的"才行
        return (execution.state() == ToolExecutionState.SUCCEEDED && execution.result().success())
                || (execution.state() == ToolExecutionState.FAILED && !execution.result().success());
    }

    private boolean timelineContainsToolResult(String sessionId, String runId, String toolCallId) {
        return timelineStore.loadMessages(sessionId, MAX_RETURNED_EVENTS).stream()
                .anyMatch(message -> message.isToolResult()
                        && runId.equals(message.runId())
                        && toolCallId.equals(message.toolCallId()));
    }

    /**
     * 记录 AgentRunBudget 快照然后更新 AgentRunRecord 并把数据库中的数据更新
     */
    private void checkpoint(String runId,
                            AgentRunPhase phase,
                            ToolCallRequest pendingToolCall,
                            List<ToolCallResult> toolResults,
                            List<String> usedTools,
                            boolean usedRag,
                            AgentRunBudget budget) {
        List<ToolCallResult> persistedResults = List.copyOf(toolResults);
        List<String> persistedTools = List.copyOf(usedTools);
        // 记录当前的快照
        AgentRunBudgetSnapshot snapshot = budget.snapshot();
        runStore.update(runId, current -> current.checkpoint(
                phase, pendingToolCall, persistedResults, persistedTools, usedRag, snapshot
        ));
    }

    private AgentRuntimeResult pauseAtCheckpoint(String runId,
                                                 String sessionId,
                                                 String userId,
                                                 AgentRunBudget budget,
                                                 AgentEventListener listener) {
        budget.pauseExecution();
        AgentRunBudgetSnapshot pausedBudget = budget.snapshot();
        // 因为暂停是通过其他接口然后把暂停标志持久化到数据库，agent 运行过程中是分阶段检查暂停标志的，当 agent 检查到暂停标志的时候 agent 可以已经执行结束了。所以这里面存在时间差
        AgentRunRecord paused = runStore.update(runId, current -> {
            // ① 已经结束了（COMPLETED/FAILED/BLOCKED）→ 不暂停，原样返回
            // ② 正在等审批 → 不暂停，原样返回
            if (isTerminal(current.state()) || current.state() == AgentRunState.WAITING_APPROVAL) {
                return current;// 不修改业务状态，但仍会产生一次版本更新
            }
            // ③ 正常 → 改为 PAUSED，存预算快照
            return current.paused(pausedBudget);
        });
        // 如果没能把状态设置为 PAUSED，说明已经结束了，返回结束状态（COMPLETED/FAILED/BLOCKED/WAITING_APPROVAL）
        if (paused.state() != AgentRunState.PAUSED) {
            return resultFromStored(paused, inferStoredStopReason(paused));
        }
        // 如果暂停成功，往前端推送消息
        publish(sessionId, userId, runId, AgentEventType.RUN_PAUSED,
                "agent run paused at durable checkpoint", Map.of(
                        "phase", paused.phase().name(),
                        "pendingToolCallId", paused.pendingToolCall() == null
                                ? ""
                                : paused.pendingToolCall().requestId(),
                        "budget", budgetPayload(pausedBudget)
                ), listener);
        return new AgentRuntimeResult(
                runId,
                sessionId,
                AgentRunState.PAUSED,
                AgentStopReason.PAUSED,
                "Agent 已暂停，可使用同一 Run ID 继续执行。",
                paused.approvalId(),
                pausedBudget,
                timelineStore.loadEvents(runId, MAX_RETURNED_EVENTS)
        );
    }

    private AgentRuntimeResult finishFinalAnswer(AgentRequest request,
                                                 String runId,
                                                 String sessionId,
                                                 String userId,
                                                 String answer,
                                                 List<ToolCallResult> toolResults,
                                                 List<String> usedTools,
                                                 boolean usedRag,
                                                 AgentRunBudget budget,
                                                 AgentEventListener listener,
                                                 StreamingModelDeltaPublisher modelDeltaPublisher) {
        GuardrailDecision outputDecision = guardrailService.checkOutput(answer);
        if (outputDecision.action() == GuardrailAction.BLOCK) {
            return finish(
                    runId, sessionId, userId, AgentRunState.BLOCKED, AgentStopReason.GUARDRAIL_BLOCKED,
                    "回答被输出安全策略拦截：" + outputDecision.reason(), "", toolResults, usedTools,
                    usedRag, true, budget, listener
            );
        }
        String safeAnswer = outputDecision.action() == GuardrailAction.REDACT && outputDecision.safeContent() != null
                ? outputDecision.safeContent()
                : answer;
        if (safeAnswer == null || safeAnswer.isBlank()) {
            safeAnswer = "模型未生成有效回答，请补充问题后重试。";
        }
        modelDeltaPublisher.complete(safeAnswer);
        AgentRunRecord current = runStore.find(runId).orElse(null);
        if (current != null
                && current.inputCheckpointEnabled()// 条件1：这个 Run 启用了输入检查点
                && current.followUpCount() < current.maxFollowUps()) {// 条件2：还有续跑次数没用完
            budget.pauseExecution();// 冻结预算计时
            // 持久化暂停
            AgentContinuationTransition transition = continuationStore().checkpointWaitingInput(
                    runId,
                    current.version(),
                    safeAnswer,
                    budget.snapshot(),
                    AgentMessageDraft.assistant(safeAnswer, tokenEstimator.estimate(safeAnswer)),
                    new AgentEventDraft(
                            AgentEventType.RUN_WAITING_INPUT,
                            "run reached a durable input checkpoint",
                            Map.of(
                                    "state", AgentRunState.WAITING_INPUT.name(),
                                    "stopReason", AgentStopReason.WAITING_INPUT.name(),
                                    "followUpCount", current.followUpCount(),
                                    "maxFollowUps", current.maxFollowUps(),
                                    "budget", budgetPayload(budget.snapshot())
                            )
                    )
            );
            dispatch(transition.event(), listener);
            return new AgentRuntimeResult(
                    runId,
                    sessionId,
                    AgentRunState.WAITING_INPUT,// 状态 = 等待输入
                    AgentStopReason.WAITING_INPUT,// 停止原因 = 等待输入
                    safeAnswer,
                    "",
                    budget.snapshot(),
                    timelineStore.loadEvents(runId, MAX_RETURNED_EVENTS)
            );
        }
        timelineStore.appendMessages(sessionId, userId, runId, List.of(
                AgentMessageDraft.assistant(safeAnswer, tokenEstimator.estimate(safeAnswer))
        ));
        return finish(
                runId, sessionId, userId, AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                safeAnswer, "", toolResults, usedTools, usedRag, false, budget, listener
        );
    }

    private AgentRuntimeResult finishBudgetStop(AgentRequest request,
                                                String runId,
                                                String sessionId,
                                                String userId,
                                                AgentStopReason stopReason,
                                                List<ToolCallResult> toolResults,
                                                List<String> usedTools,
                                                boolean usedRag,
                                                AgentRunBudget budget,
                                                AgentEventListener listener) {
        String answer = switch (stopReason) {
            case MAX_TURNS -> "Agent 已达到最大推理轮次，任务未能在预算内完成。";
            case MODEL_BUDGET_EXHAUSTED -> "Agent 已达到模型调用或 Token/成本预算上限。";
            case TOOL_BUDGET_EXHAUSTED -> "Agent 已达到工具调用预算上限。";
            case TIMEOUT -> "Agent 运行超时，已安全终止。";
            case CANCELLED -> "Agent 运行已取消。";
            default -> "Agent 因运行预算限制而终止。";
        };
        AgentRunState state = stopReason == AgentStopReason.CANCELLED
                ? AgentRunState.REJECTED
                : AgentRunState.FAILED;
        return finish(runId, sessionId, userId, state, stopReason, answer, "", toolResults, usedTools,
                usedRag, false, budget, listener);
    }

    private AgentRuntimeResult finish(String runId,
                                      String sessionId,
                                      String userId,
                                      AgentRunState state,
                                      AgentStopReason stopReason,
                                      String answer,
                                      String approvalId,
                                      List<ToolCallResult> toolResults,
                                      List<String> usedTools,
                                      boolean usedRag,
                                      boolean guardrailBlocked,
                                      AgentRunBudget budget,
                                      AgentEventListener listener) {
        AgentRunPhase phase = switch (state) {
            case COMPLETED -> AgentRunPhase.FINISHED;
            case BLOCKED, REJECTED -> AgentRunPhase.BLOCKED;
            case WAITING_APPROVAL -> AgentRunPhase.WAITING_APPROVAL;
            case WAITING_INPUT -> AgentRunPhase.WAITING_INPUT;
            default -> AgentRunPhase.FAILED;
        };
        runStore.update(runId, current -> current.finished(
                state,
                phase,
                answer,
                state == AgentRunState.COMPLETED ? "" : stopReason.name(),
                toolResults,
                usedTools,
                usedRag,
                guardrailBlocked,
                budget.snapshot()
        ));
        AgentEventType eventType = state == AgentRunState.COMPLETED
                ? AgentEventType.RUN_COMPLETED
                : state == AgentRunState.REJECTED
                ? AgentEventType.RUN_CANCELLED
                : AgentEventType.RUN_FAILED;
        publish(sessionId, userId, runId, eventType, answer,
                Map.of("state", state.name(), "stopReason", stopReason.name(), "budget", budgetPayload(budget.snapshot())),
                listener);
        return new AgentRuntimeResult(
                runId,
                sessionId,
                state,
                stopReason,
                answer,
                approvalId,
                budget.snapshot(),
                timelineStore.loadEvents(runId, MAX_RETURNED_EVENTS)
        );
    }

    private AgentRuntimeResult finishUnexpectedFailure(String runId,
                                                       String sessionId,
                                                       String userId,
                                                       AgentRunBudget budget,
                                                       AgentEventListener listener,
                                                       RuntimeException failure) {
        LOGGER.error("Unhandled Agent Runtime failure for run {}", runId, failure);
        AgentRunRecord current = runStore.find(runId).orElse(null);
        if (current != null && (isTerminal(current.state())
                || current.state() == AgentRunState.PAUSED
                || current.state() == AgentRunState.PAUSE_REQUESTED)) {
            return resultFromStored(current, inferStoredStopReason(current));
        }
        return finish(
                runId,
                sessionId,
                userId,
                AgentRunState.FAILED,
                AgentStopReason.INTERNAL_ERROR,
                "Agent Runtime 发生未预期异常，运行已安全终止。",
                "",
                current == null ? List.of() : current.toolResults(),
                current == null ? List.of() : current.usedTools(),
                current != null && current.usedRag(),
                current != null && current.blockedByGuardrail(),
                budget,
                listener
        );
    }

    private ToolCallResult appendToolResult(String sessionId,
                                            String userId,
                                            String runId,
                                            String toolCallId,
                                            ToolCallResult result,
                                            boolean rawPersisted) {
        ToolCallResult projected = toolResultProjector.project(toolCallId, result, rawPersisted);
        timelineStore.appendMessages(sessionId, userId, runId, List.of(
                AgentMessageDraft.toolResult(
                        toolCallId,
                        projected.toolName(),
                        projected.success(),
                        projected.content(),
                        projected.errorMessage(),
                        projected.metadata(),
                        tokenEstimator.estimate(projected.content())
                                + tokenEstimator.estimate(projected.errorMessage())
                )
        ));
        return projected;
    }

    /**
     * 终态人工复核也必须闭合 ASSISTANT_TOOL_CALL -> TOOL_RESULT。
     *
     * <p>MANUAL_REVIEW 表示副作用结果不能被证明，不表示工具调用仍在等待。若直接终止 Run 而不写
     * ToolResult，后续同一会话会留下孤立 ToolCall，Context Manager 为保护模型协议只能拒绝投影。</p>
     */
    private ToolCallResult appendTerminalManualReviewResult(String sessionId,
                                                            String userId,
                                                            String runId,
                                                            ToolCallRequest request,
                                                            ToolCallResult underlyingResult,
                                                            String reason,
                                                            String approvalId,
                                                            AgentEventListener listener) {
        String reviewReason = underlyingResult != null
                && underlyingResult.errorMessage() != null
                && !underlyingResult.errorMessage().isBlank()
                ? underlyingResult.errorMessage()
                : reason == null || reason.isBlank()
                ? "tool outcome requires manual review"
                : reason;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(
                underlyingResult == null ? Map.of() : underlyingResult.metadata()
        );
        metadata.put("outcome", "MANUAL_REVIEW");
        metadata.put("manualReview", true);
        metadata.put("retryable", false);
        metadata.put("reviewReason", reviewReason);
        if (approvalId != null && !approvalId.isBlank()) {
            metadata.put("approvalId", approvalId);
        }
        if (underlyingResult != null) {
            metadata.put("underlyingResultSuccess", underlyingResult.success());
        }
        ToolCallResult rawResult = new ToolCallResult(
                request.toolName(),
                false,
                underlyingResult == null || underlyingResult.content() == null
                        ? ""
                        : underlyingResult.content(),
                reviewReason,
                Map.copyOf(metadata)
        );
        ToolCallResult projected = toolResultProjector.project(request.requestId(), rawResult, false);
        if (!timelineContainsToolResult(sessionId, runId, request.requestId())) {
            projected = appendToolResult(
                    sessionId, userId, runId, request.requestId(), rawResult, false
            );
        }
        publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                "tool outcome requires manual review",
                toolResultPayload(request.requestId(), projected), listener);
        return projected;
    }

    /**
     * 放弃暂停 Run 时也要闭合可能已经写入时间线的 ToolCall，否则同一会话的新 Run 会读到孤立调用。
     * 这里仅记录已知结果或“结果未知且旧 Run 已放弃”的终态事实，不重新执行工具。
     */
    private CancelledPendingToolClosure closePendingToolCallForCancellation(AgentRunRecord stored,
                                                                            String userId) {
        ToolCallRequest pending = stored.pendingToolCall();
        if (pending == null
                || timelineContainsToolResult(stored.conversationId(), stored.runId(), pending.requestId())) {
            return null;
        }
        ToolExecutionRecord execution = toolExecutionStore.findToolExecution(pending.requestId()).orElse(null);
        boolean certain = hasCertainPersistedResult(stored.runId(), pending, execution);
        ToolCallResult underlying = certain ? execution.result() : null;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(
                underlying == null ? Map.of() : underlying.metadata()
        );
        metadata.put("outcome", "RUN_ABANDONED");
        metadata.put("runAbandoned", true);
        metadata.put("retryable", false);
        metadata.put("executionObserved", execution != null);
        metadata.put("outcomeKnown", certain);
        if (execution != null) {
            metadata.put("toolExecutionState", execution.state().name());
        }
        String error = certain
                ? underlying.errorMessage()
                : "Run was abandoned by the user; the pending tool outcome is not asserted.";
        ToolCallResult rawResult = new ToolCallResult(
                pending.toolName(),
                certain && underlying.success(),
                certain ? underlying.content() : "",
                error,
                Map.copyOf(metadata)
        );
        ToolCallResult projected = appendToolResult(
                stored.conversationId(), userId, stored.runId(), pending.requestId(), rawResult, certain
        );
        publish(
                stored.conversationId(), userId, stored.runId(), AgentEventType.TOOL_COMPLETED,
                "pending tool call closed because the run was abandoned",
                toolResultPayload(pending.requestId(), projected), AgentEventListener.NOOP
        );
        return new CancelledPendingToolClosure(projected, execution != null);
    }

    private record CancelledPendingToolClosure(ToolCallResult result, boolean executionObserved) {
    }

    AgentToolCall assignExecutionId(AgentToolCall modelCall) {
        return new AgentToolCall(
                UUID.randomUUID().toString(),
                modelCall.toolName(),
                modelCall.arguments(),
                modelCall.reason()
        );
    }

    private Map<String, Object> providerToolCallMetadata(AgentToolCall call,
                                                         String modelToolCallId,
                                                         String reasoningContent) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", call.reason());
        metadata.put(AgentProviderMetadata.MODEL_TOOL_CALL_ID, modelToolCallId);
        if (reasoningContent != null && !reasoningContent.isBlank()) {
            metadata.put(AgentProviderMetadata.REASONING_CONTENT, reasoningContent);
        }
        return Map.copyOf(metadata);
    }

    private LlmUsage effectiveUsage(AgentModelTurn turn, AgentContextView context) {
        // ① API 返回了真实 token 数 → 直接用
        if (turn.usage() != null && turn.usage().hasTokenUsage()) {
            return turn.usage();
        }
        // ② API 没返回 token 数（部分模型不返回） → 项目自己估算
        long output = tokenEstimator.estimate(turn.rawResponse());
        return new LlmUsage(
                context.estimatedTokens(),// prompt token = 我们算的上下文大小
                output,// output token = 估的回答长度
                context.estimatedTokens() + output,// total
                0,
                0,
                turn.usage() == null ? "" : turn.usage().model(),
                "runtime-estimate"// 标记：这是估算值，不是真实值
        );
    }

    /**
     * 上下文消息预算
     */
    private long contextMessageBudget(AgentExecutionProfile profile) {
        // token 估算
        long staticTokens = tokenEstimator.estimate(profile.systemPrompt()) + 700;
        for (ToolDefinition definition : capabilitiesFor(profile)) {// 保留允许调用的工具
            staticTokens += tokenEstimator.estimate(definition.name())
                    + tokenEstimator.estimate(definition.description())
                    + tokenEstimator.estimate(definition.inputSchema());
        }
        long availableWindow = Math.max(1, properties.getModelContextWindowTokens())
                - Math.max(0, properties.getContextOutputReserveTokens())
                - Math.max(0, properties.getContextSafetyMarginTokens())
                - staticTokens;
        return Math.max(1, Math.min(profile.limits().maxInputTokens(), availableWindow));
    }

    /**
     * 保留允许调用的工具
     */
    private List<ToolDefinition> capabilitiesFor(AgentExecutionProfile profile) {
        return capabilityRegistry.listCapabilities().stream()
                .filter(definition -> profile.allows(definition.name()))
                .toList();
    }

    /**
     * 发给 LLM 前过滤，保存 agent 白名单工具和当前阶段可见工具
     */
    private List<ToolDefinition> capabilitiesFor(AgentExecutionProfile profile,
                                                  Map<String, Object> requestMetadata,
                                                  List<String> usedTools) {
        return capabilityRegistry.listCapabilities().stream()
                .filter(definition -> profile.allows(definition.name()))
                .filter(definition -> AgentCapabilityVisibilityPolicy.visibleToModel(
                        definition, requestMetadata, usedTools))
                .toList();
    }

    private Optional<GuardrailDecision> followUpGuardrailDecision(AgentRunRecord stored,
                                                                  AgentFollowUpInput input) {
        for (AgentFollowUpGuardrailPolicy policy : followUpGuardrailPolicies) {
            try {
                Optional<GuardrailDecision> decision = policy.evaluate(stored, input);
                if (decision.isPresent()) {
                    return decision;
                }
            }
            catch (RuntimeException exception) {
                LOGGER.warn("deterministic follow-up guardrail failed; falling back to general input guardrail; policy={}",
                        policy.getClass().getSimpleName(), exception);
            }
        }
        return Optional.empty();
    }

    /**
     * 默认执行配置文件，包括 agent 能使用的工具、系统提示词、agent 运行时的限制条件、启用长期内存存储
     */
    private AgentExecutionProfile defaultExecutionProfile() {
        // 列出 agent 的能力，也就是 agent 能访问的工具，包括本地定义的工具和 mcp 提供的工具，收集成工具名称集合
        Set<String> capabilities = capabilityRegistry.listCapabilities().stream()
                .map(ToolDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AgentExecutionProfile(
                "main-agent",
                properties.getDefaultSystemPrompt(),
                capabilities,
                AgentRunLimits.from(properties),
                true
        );
    }

    private boolean isContextOverflow(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof LlmCallException llmCallException
                    && "CONTEXT_OVERFLOW".equals(llmCallException.errorType())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String modelErrorType(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof LlmCallException llmCallException) {
                return llmCallException.errorType();
            }
            current = current.getCause();
        }
        return failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
    }

    /**
     * 获取 session 租约
     */
    private void acquireRun(String sessionId,
                            String runId,
                            String leaseOwnerId,
                            AgentRunBudget budget) {
        // 获取 session 租赁，防止同一 session 被多个 run 并发执行
        runControlStore.acquireSessionLease(sessionId, runId, leaseOwnerId, sessionLeaseDuration());
        activeBudgets.put(runId, budget);
    }

    private void releaseRun(String sessionId, String runId, String leaseOwnerId) {
        activeBudgets.remove(runId);
        try {
            // 释放 session 租约
            runControlStore.releaseSessionLease(sessionId, leaseOwnerId);
        }
        catch (RuntimeException exception) {
            // 租约有过期时间；释放失败不能覆盖已经持久化的 Agent 最终结果。
            LOGGER.warn("Failed to release Agent session lease for run {}", runId, exception);
        }
    }

    private String newLeaseOwnerId(String runId) {
        return runId + ":" + UUID.randomUUID();
    }

    /**
     * 查看是否有 agent 的取消请求
     */
    private void synchronizeCancellation(String runId, AgentRunBudget budget) {
        // 查看是否有 agent 的取消请求
        if (runControlStore.cancellationRequested(runId)) {
            budget.cancel();
        }
        if (runControlStore.pauseRequested(runId)) {
            budget.requestPause();
        }
    }

    private boolean pauseRequested(String runId, AgentRunBudget budget) {
        synchronizeCancellation(runId, budget);
        return budget.pauseRequested() && budget.currentStopReason().isEmpty();
    }

    /**
     * 会话租赁期限
     */
    private Duration sessionLeaseDuration() {
        return Duration.ofMillis(Math.max(60_000, properties.getMaxRunDurationMillis() + 60_000));
    }

    private boolean isTerminal(AgentRunState state) {
        return state == AgentRunState.COMPLETED
                || state == AgentRunState.BLOCKED
                || state == AgentRunState.FAILED
                || state == AgentRunState.REJECTED
                || state == AgentRunState.MANUAL_REVIEW;
    }

    /**
     * 检查配置的 AgentRunStore 是否实现了 AgentContinuationStore 接口（即支持持久化 WAITING_INPUT 暂停/恢复）。不支持就 fail-fast——因为"启用续跑但存储层不支持"会导致后面暂停无法落库
     */
    private AgentContinuationStore continuationStore() {
        if (runStore instanceof AgentContinuationStore store) {
            return store;
        }
        throw new IllegalStateException(
                "configured AgentRunStore does not support durable WAITING_INPUT transitions"
        );
    }

    private void validateFollowUpBudget(AgentRunRecord stored, AgentFollowUpInput input) {
        AgentExecutionProfile profile = stored.executionProfile() == null
                ? defaultExecutionProfile()
                : stored.executionProfile();
        AgentRunBudgetSnapshot snapshot = stored.budgetSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("WAITING_INPUT run is missing its cumulative budget snapshot");
        }
        long remainingTools = Math.max(0, profile.limits().maxToolCalls() - snapshot.toolCalls());
        long remainingTokens = Math.max(
                0,
                profile.limits().maxInputTokens() + profile.limits().maxOutputTokens()
                        - snapshot.inputTokens() - snapshot.outputTokens()
        );
        if (input.additionalToolBudget() > remainingTools
                || input.additionalTokenBudget() > remainingTokens) {
            throw new IllegalArgumentException("follow-up allocation exceeds the original run budget");
        }
    }

    /**
     * 往数据库中添加 agent 事件并把事件推送前端
     */
    private AgentEvent publish(String sessionId,
                               String userId,
                               String runId,
                               AgentEventType type,
                               String content,
                               Map<String, Object> payload,
                               AgentEventListener listener) {
        // 往数据库中添加 agent 事件
        AgentEvent event = timelineStore.appendEvent(
                sessionId,
                userId,
                runId,
                new AgentEventDraft(type, content, payload)
        );
        // 把事件推送前端
        dispatch(event, listener);
        return event;
    }

    /**
     * 把事件推送前端
     */
    private void dispatch(AgentEvent event, AgentEventListener listener) {
        if (event == null || listener == null) {
            return;
        }
        try {
            // 监听器，当有操作的时候会往前端推送事件（streaming 式）
            listener.onEvent(event);
        }
        catch (RuntimeException ignored) {
            // Event transport failure cannot roll back already persisted Agent execution state.
        }
    }

    /**
     * 将 Provider 的细粒度 Token/chunk 合并成可审计的 MODEL_DELTA。
     *
     * <p>增量末尾保留一小段窗口后再发布，用于识别横跨多个 Provider chunk 的手机号、
     * 身份证、API Key 等敏感内容；同时按最小字符数聚合，避免逐 Token 写事件表。</p>
     */
    private final class StreamingModelDeltaPublisher {

        private final String sessionId;
        private final String userId;
        private final String runId;
        private final int turn;
        private final AgentEventListener listener;
        private final StringBuilder rawAnswer = new StringBuilder();
        private final StringBuilder emittedSafeAnswer = new StringBuilder();
        private int deltaIndex;

        private StreamingModelDeltaPublisher(String sessionId,
                                             String userId,
                                             String runId,
                                             int turn,
                                             AgentEventListener listener) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.runId = runId;
            this.turn = turn;
            this.listener = listener;
        }

        private synchronized void accept(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            // ① 拼接原始回答
            rawAnswer.append(delta);// "退款流程是：首先..."
            // ② 输出护栏预览（边吐边检查！）
            GuardrailDecision preview = guardrailService.previewOutput(rawAnswer.toString());
            if (preview == null || preview.action() == GuardrailAction.BLOCK) {
                return;// 检测到敏感内容 → 不推送，但不中断生成
            }
            String safe = preview.action() == GuardrailAction.REDACT && preview.safeContent() != null
                    ? preview.safeContent()
                    : rawAnswer.toString();
            // ③ holdback：保留最后 16 个字符不推送，防止电话号码、身份证号、API Key 跨 token 边界泄露
            int holdback = Math.max(16, properties.getStreamOutputGuardrailHoldbackChars());
            emitThrough(safe, Math.max(0, safe.length() - holdback), false);
        }

        private synchronized void complete(String safeAnswer) {
            // LLM 回答完毕 → 推送剩余文本（包括 holdback 部分）
            emitThrough(safeAnswer == null ? "" : safeAnswer,
                    safeAnswer == null ? 0 : safeAnswer.length(), true);
        }

        private void emitThrough(String safeAnswer, int requestedEnd, boolean finalChunk) {
            String emitted = emittedSafeAnswer.toString();
            if (!safeAnswer.startsWith(emitted)) {
                // 完整 Guardrail 改写了已经发送部分时不能撤回 SSE；RUN_COMPLETED 会给出权威安全文本。
                return;
            }
            int end = Math.max(emitted.length(), Math.min(requestedEnd, safeAnswer.length()));
            // ① 去重：只推还没发过的部分
            int available = end - emitted.length();
            int minChars = Math.max(1, properties.getStreamModelDeltaMinChars());
            // ② 聚合：最少攒够 minChars 才发一次（避免一个字符发一个事件）
            if (!finalChunk && available < minChars) {
                return;
            }
            if (available <= 0) {
                return;
            }
            String delta = safeAnswer.substring(emitted.length(), end);
            emittedSafeAnswer.append(delta);
            deltaIndex++;
            // ③ 推 MODEL_DELTA 事件
            publish(sessionId, userId, runId, AgentEventType.MODEL_DELTA, delta,
                    Map.of(
                            "turn", turn,
                            "deltaIndex", deltaIndex,
                            "finalChunk", finalChunk,
                            "guardrailHoldbackChars", properties.getStreamOutputGuardrailHoldbackChars()
                    ), listener);
        }
    }

    private Map<String, Object> toolResultPayload(String toolCallId, ToolCallResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
        payload.put("toolName", result.toolName());
        payload.put("success", result.success());
        payload.put("error", result.errorMessage() == null ? "" : result.errorMessage());
        payload.put("metadata", result.metadata());
        return Map.copyOf(payload);
    }

    private Map<String, Object> budgetPayload(AgentRunBudgetSnapshot budget) {
        return Map.of(
                "turns", budget.turns(),
                "modelCalls", budget.modelCalls(),
                "toolCalls", budget.toolCalls(),
                "inputTokens", budget.inputTokens(),
                "outputTokens", budget.outputTokens(),
                "estimatedCost", budget.estimatedCost(),
                "deadline", budget.deadline().toString(),
                "cancelled", budget.cancelled(),
                "remainingExecutionMillis", budget.remainingExecutionMillis(),
                "executionPaused", budget.executionPaused()
        );
    }

    private Map<String, Object> contextEventPayload(AgentContextView before,
                                                     AgentContextView after,
                                                     String reason,
                                                     boolean compactionRequested) {
        return contextEventPayload(before, after, reason, compactionRequested, Map.of());
    }

    private Map<String, Object> contextEventPayload(AgentContextView before,
                                                     AgentContextView after,
                                                     String reason,
                                                     boolean compactionRequested,
                                                     Map<String, Object> extras) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (after != null) {
            payload.putAll(after.metadata());
            payload.put("messageCount", after.messages().size());
            payload.put("estimatedTokens", after.estimatedTokens());
            payload.put("omittedMessages", after.omittedMessages());
            payload.put("afterMessageCount", after.messages().size());
            payload.put("afterEstimatedTokens", after.estimatedTokens());
            payload.put("afterOmittedMessages", after.omittedMessages());
            payload.put("afterCoversThroughSequence",
                    contextLongMetric(after, "coversThroughSequence", 0));
            payload.put("coversThroughSequence",
                    contextLongMetric(after, "coversThroughSequence", 0));
            payload.put("compactionPerformed", contextBooleanMetric(
                    after, "compactionPerformed", compactionRequested));
        }
        else {
            payload.put("messageCount", 0);
            payload.put("estimatedTokens", 0L);
            payload.put("omittedMessages", 0);
            payload.put("afterMessageCount", 0);
            payload.put("afterEstimatedTokens", 0L);
            payload.put("afterOmittedMessages", 0);
            payload.put("afterCoversThroughSequence", 0L);
            payload.put("coversThroughSequence", 0L);
            payload.put("compactionPerformed", compactionRequested);
        }
        payload.put("reason", reason == null || reason.isBlank() ? "projection" : reason);
        payload.put("compactionRequested", compactionRequested);
        if (compactionRequested && before != null) {
            payload.put("beforeMessageCount", before.messages().size());
            payload.put("beforeEstimatedTokens", before.estimatedTokens());
            payload.put("beforeOmittedMessages", before.omittedMessages());
            payload.put("beforeCoversThroughSequence",
                    contextLongMetric(before, "coversThroughSequence", 0));
        }
        if (extras != null) {
            extras.forEach((key, value) -> {
                if (key != null && value != null) {
                    payload.put(key, value);
                }
            });
        }
        return Map.copyOf(payload);
    }

    private long contextLongMetric(AgentContextView context, String key, long fallback) {
        if (context == null || key == null) {
            return fallback;
        }
        Object value = context.metadata().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        }
        catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean contextBooleanMetric(AgentContextView context, String key, boolean fallback) {
        if (context == null || key == null) {
            return fallback;
        }
        Object value = context.metadata().get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private AgentRuntimeResult resultFromStored(AgentRunRecord stored, AgentStopReason stopReason) {
        AgentRunBudgetSnapshot budget = stored.budgetSnapshot() == null
                ? new AgentRunBudget(AgentRunLimits.from(properties)).snapshot()
                : stored.budgetSnapshot();
        return new AgentRuntimeResult(
                stored.runId(),
                stored.conversationId(),
                stored.state(),
                stopReason,
                stored.answer(),
                stored.approvalId(),
                budget,
                timelineStore.loadEvents(stored.runId(), MAX_RETURNED_EVENTS)
        );
    }

    /**
     * 根据 agent 的运行状态推断停止原因
     */
    private AgentStopReason inferStoredStopReason(AgentRunRecord stored) {
        return switch (stored.state()) {
            case COMPLETED -> AgentStopReason.COMPLETED;
            case WAITING_APPROVAL -> AgentStopReason.WAITING_APPROVAL;
            case WAITING_INPUT -> AgentStopReason.WAITING_INPUT;
            case RUNNING -> AgentStopReason.IN_PROGRESS;
            case PAUSE_REQUESTED, PAUSED -> AgentStopReason.PAUSED;
            case BLOCKED, REJECTED -> AgentStopReason.GUARDRAIL_BLOCKED;
            case MANUAL_REVIEW -> AgentStopReason.TOOL_ERROR;
            default -> AgentStopReason.INTERNAL_ERROR;
        };
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
