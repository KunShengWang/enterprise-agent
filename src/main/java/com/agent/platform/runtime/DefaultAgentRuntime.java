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

/**
 * 单一模型驱动 Agent Runtime。
 *
 * <p>同步接口与 SSE 接口只需选择不同的 AgentEventListener，执行语义完全一致。
 * 每一次模型调用、工具策略、工具执行和终止原因都先写入数据库事件时间线。</p>
 */
@Service
public class DefaultAgentRuntime implements AgentRuntime {

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
    private final ApprovalService approvalService;
    private final TokenEstimator tokenEstimator;
    private final AgentRunControlStore runControlStore;

    private final MemoryService memoryService;
    private final LlmCostCalculator costCalculator;
    private final ToolResultProjector toolResultProjector;

    /** 仅保存本实例中正在执行的取消句柄，持久取消事实仍写入 AgentRunControlStore。 */
    private final ConcurrentMap<String, AgentRunBudget> activeBudgets = new ConcurrentHashMap<>();

    public DefaultAgentRuntime(AgentProperties properties,
                               AgentTimelineStore timelineStore,
                               AgentRunStore runStore,
                               ToolExecutionStore toolExecutionStore,
                               AgentContextManager contextManager,
                               AgentModelGateway modelGateway,
                               AgentCapabilityRegistry capabilityRegistry,
                               AgentToolRuntime toolRuntime,
                               GuardrailService guardrailService,
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
        AgentExecutionProfile profile = executionProfile == null
                ? defaultExecutionProfile()
                : executionProfile;
        // 该接口是同步接口，使用空监听器，也就是如果有事件发送不做任何处理（不推送前端）
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
            runStore.create(AgentRunRecord.create(
                    runId, runId, sessionId, originalRequest, profile, budget.snapshot()
            ));
            runCreated = true;
            // 往数据库中添加 agent 事件
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
            return finishUnexpectedFailure(
                    runId, sessionId, userId, budget, effectiveListener, exception
            );
        }
        finally {
            releaseRun(sessionId, runId, leaseOwnerId);
        }
    }

    @Override
    public AgentRuntimeResult resume(String runId, AgentEventListener listener) {
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        AgentRunRecord stored = runStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        if (stored.state() == AgentRunState.RUNNING) {
            return recoverRunning(stored, effectiveListener);
        }
        if (stored.state() != AgentRunState.WAITING_APPROVAL) {
            return resultFromStored(stored, inferStoredStopReason(stored));
        }
        ApprovalRecord approval = approvalService.find(stored.approvalId())
                .orElseThrow(() -> new IllegalArgumentException("approval not found: " + stored.approvalId()));
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
            acquireRun(sessionId, runId, leaseOwnerId, budget);
            acquired = true;
            Optional<AgentRunRecord> claim = runStore.claimForResume(runId);
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
            synchronizeCancellation(runId, budget);
            // 在 agent 的执行轮次之前判断是否取消 agent
            Optional<AgentStopReason> resumeStop = budget.beforeTurn();
            if (resumeStop.isPresent()) {
                return finishBudgetStop(claimed.request(), runId, sessionId, userId, resumeStop.get(),
                        toolResults, usedTools, claimed.usedRag(), budget, effectiveListener);
            }

            ToolCallResult result;
            if (approval.status() == ApprovalStatus.REJECTED || approval.status() == ApprovalStatus.EXPIRED) {
                String approvalError = approval.status() == ApprovalStatus.EXPIRED
                        ? "human approval expired"
                        : "human approval rejected: " + approval.decisionReason();
                result = new ToolCallResult(
                        approval.toolCallRequest().toolName(),
                        false,
                        "",
                        approvalError,
                        Map.of("approvalId", approval.approvalId(), "approvalStatus", approval.status().name())
                );
            }
            else {
                ToolDefinition definition = capabilityRegistry.findCapability(approval.toolCallRequest().toolName())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "approved capability no longer exists: " + approval.toolCallRequest().toolName()
                        ));
                AgentToolRuntimeResult execution = toolRuntime.executeApproved(
                        approval,
                        definition,
                        ToolPolicyContext.from(runId, sessionId, userId,
                                claimed.request() == null ? Map.of() : claimed.request().metadata())
                );
                if (execution.status() == AgentToolExecutionStatus.MANUAL_REVIEW) {
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
            checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                    toolResults, usedTools, claimed.usedRag(), budget);
            publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                    result.success() ? "approved tool completed" : "approved tool returned failure",
                    toolResultPayload(approval.toolCallRequest().requestId(), result), effectiveListener);
            AgentRequest request = claimed.request();
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
        else if (persisted && stored.state() == AgentRunState.WAITING_APPROVAL) {
            AgentRunBudget cancelledBudget = new AgentRunBudget(AgentRunLimits.from(properties));
            cancelledBudget.cancel();
            finishBudgetStop(
                    stored.request(), stored.runId(), stored.conversationId(),
                    normalize(stored.userId(), DEFAULT_USER_ID), AgentStopReason.CANCELLED,
                    stored.toolResults(), stored.usedTools(), stored.usedRag(), cancelledBudget,
                    AgentEventListener.NOOP
            );
        }
        return persisted;
    }

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
            AgentContextView context = contextManager.project(
                    sessionId,
                    userId,
                    request.question(),
                    contextTokenBudget
            );
            if (context.omittedMessages() > 0) {
                // 上下文压缩
                context = contextManager.compact(
                        sessionId, userId, runId, request.question(), contextTokenBudget, "context_budget"
                );
            }
            publish(sessionId, userId, runId,
                    context.compacted() ? AgentEventType.CONTEXT_COMPACTED : AgentEventType.CONTEXT_PREPARED,
                    "context projected",
                    Map.of(
                            "messageCount", context.messages().size(),
                            "estimatedTokens", context.estimatedTokens(),
                            "omittedMessages", context.omittedMessages()
                    ),
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
            // 在模型调用之前判断是否取消 agent
            Optional<AgentStopReason> modelStop = budget.beforeModelCall();
            if (modelStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, modelStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }

            publish(sessionId, userId, runId, AgentEventType.MODEL_STARTED,
                    "model turn started", Map.of("turn", budget.snapshot().turns()), listener);
            checkpoint(runId, AgentRunPhase.MODEL_CALL, null,
                    toolResults, usedTools, usedRag, budget);
            AgentModelTurn modelTurn;
            while (true) {
                try {
                    modelTurn = modelGateway.nextTurn(new AgentModelRequest(
                            runId,
                            sessionId,
                            profile.systemPrompt(),
                            context.messages(),
                            capabilitiesFor(profile),
                            request.metadata()
                    ));
                    break;
                }
                catch (RuntimeException modelFailure) {
                    // 判断是否是上下文溢出异常
                    boolean contextOverflow = isContextOverflow(modelFailure);
                    publish(sessionId, userId, runId, AgentEventType.MODEL_FAILED,
                            contextOverflow ? "model rejected context" : "model turn failed",
                            Map.of(
                                    "errorType", modelErrorType(modelFailure),
                                    "recoverableByCompaction", contextOverflow
                            ), listener);
                    if (!contextOverflow) {
                        budget.recordModelCall(new LlmUsage(0, 0, 0, 0, 0, "", "failed"), 0);
                        synchronizeCancellation(runId, budget);
                        Optional<AgentStopReason> interruptedStop = budget.currentStopReason();
                        if (interruptedStop.isPresent()) {
                            return finishBudgetStop(request, runId, sessionId, userId, interruptedStop.get(),
                                    toolResults, usedTools, usedRag, budget, listener);
                        }
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
                        context = contextManager.compact(
                                sessionId, userId, runId, request.question(), retryBudget,
                                "provider_context_overflow"// ← 压缩原因
                        );
                        publish(sessionId, userId, runId, AgentEventType.CONTEXT_COMPACTED,
                                "provider rejected context; compacted before bounded retry",
                                Map.of(
                                        "retry", contextOverflowRetries,
                                        "maxRetries", properties.getMaxContextOverflowRetries(),
                                        "messageCount", context.messages().size(),
                                        "estimatedTokens", context.estimatedTokens(),
                                        "omittedMessages", context.omittedMessages()
                                ), listener);
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
            checkpoint(runId, AgentRunPhase.MODEL_CALL, null,
                    toolResults, usedTools, usedRag, budget);
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

            synchronizeCancellation(runId, budget);
            Optional<AgentStopReason> asynchronousStop = budget.currentStopReason();
            if (asynchronousStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, asynchronousStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }
            // 本次 LLM 调用没有返回工具调用，直接对本地 LLM 答案做护栏输出并添加 LLM 返回消息，然后返回结果
            if (!modelTurn.hasToolCalls()) {
                return finishFinalAnswer(request, runId, sessionId, userId, modelTurn.assistantText(),
                        toolResults, usedTools, usedRag, budget, listener);
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

            for (AgentToolCall rawCall : modelTurn.toolCalls()) {
                synchronizeCancellation(runId, budget);
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
                                Map.of("reason", call.reason(), "modelToolCallId", modelToolCallId),
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

                boolean capabilityAllowed = profile.allows(call.toolName());
                Optional<ToolDefinition> definition = capabilityAllowed
                        ? capabilityRegistry.findCapability(call.toolName())
                        : Optional.empty();
                if (definition.isEmpty()) {
                    String errorType = capabilityAllowed ? "UNKNOWN_CAPABILITY" : "CAPABILITY_NOT_ALLOWED";
                    String errorMessage = capabilityAllowed
                            ? "unknown capability: " + call.toolName()
                            : "capability is not allowed by execution profile: " + call.toolName();
                    ToolCallResult unknown = new ToolCallResult(
                            call.toolName(), false, "", errorMessage,
                            Map.of("errorType", errorType, "profile", profile.name())
                    );
                    ToolCallResult projectedUnknown = appendToolResult(
                            sessionId, userId, runId, call.toolCallId(), unknown, false
                    );
                    toolResults.add(projectedUnknown);
                    budget.recordToolCall();
                    checkpoint(runId, AgentRunPhase.CONTEXT_PREPARATION, null,
                            toolResults, usedTools, usedRag, budget);
                    publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                            "unknown capability returned to model", toolResultPayload(call.toolCallId(), unknown), listener);
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
                if (execution.status() == AgentToolExecutionStatus.WAITING_APPROVAL) {
                    budget.pauseExecution();
                    List<ToolCallResult> persistedToolResults = List.copyOf(toolResults);
                    List<String> persistedUsedTools = List.copyOf(usedTools);
                    boolean ragUsedAtPause = usedRag;
                    runStore.update(runId, current -> current.waitingForApproval(
                            execution.approvalId(),
                            execution.request(),
                            persistedToolResults,
                            persistedUsedTools,
                            ragUsedAtPause,
                            budget.snapshot()
                    ));
                    publish(sessionId, userId, runId, AgentEventType.APPROVAL_REQUIRED,
                            "tool call is waiting for human approval",
                            Map.of(
                                    "approvalId", execution.approvalId(),
                                    "toolCallId", call.toolCallId(),
                                    "toolName", call.toolName()
                            ),
                            listener);
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
                if (execution.status() == AgentToolExecutionStatus.MANUAL_REVIEW) {
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
            acquireRun(sessionId, runId, leaseOwnerId, budget);
            acquired = true;
            AgentRunRecord current = runStore.find(runId).orElse(stored);
            if (current.state() != AgentRunState.RUNNING) {
                return resultFromStored(current, inferStoredStopReason(current));
            }
            AgentRunRecord claimed = runStore.update(runId, AgentRunRecord::claimedForRecovery);
            publish(sessionId, userId, runId, AgentEventType.RUN_RESUMED,
                    "stale running checkpoint recovered", Map.of(
                            "phase", claimed.phase().name(),
                            "resumeCount", claimed.resumeCount()
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
            return resultFromStored(stored, AgentStopReason.IN_PROGRESS);
        }
        catch (RuntimeException failure) {
            return finishUnexpectedFailure(runId, sessionId, userId, budget, listener, failure);
        }
        finally {
            if (acquired) {
                releaseRun(sessionId, runId, leaseOwnerId);
            }
        }
    }

    private AgentRuntimeResult recoverExecutingTool(AgentRunRecord claimed,
                                                     String leaseOwnerId,
                                                     String sessionId,
                                                     String userId,
                                                     AgentRunBudget budget,
                                                     AgentExecutionProfile profile,
                                                     AgentEventListener listener) {
        ToolCallRequest pending = claimed.pendingToolCall();
        ToolExecutionRecord execution = pending == null
                ? null
                : toolExecutionStore.findToolExecution(pending.requestId()).orElse(null);
        if (!hasCertainPersistedResult(claimed.runId(), pending, execution)) {
            String executionState = execution == null ? "UNKNOWN" : execution.state().name();
            return finish(
                    claimed.runId(), sessionId, userId, AgentRunState.MANUAL_REVIEW, AgentStopReason.TOOL_ERROR,
                    "工具执行检查点结果不确定，需要人工核对：" + executionState,
                    claimed.approvalId(), claimed.toolResults(), claimed.usedTools(), claimed.usedRag(),
                    claimed.blockedByGuardrail(), budget, listener
            );
        }

        ToolCallResult rawResult = execution.result();
        ToolCallResult projectedResult = toolResultProjector.project(pending.requestId(), rawResult, true);
        if (!timelineContainsToolResult(sessionId, claimed.runId(), pending.requestId())) {
            projectedResult = appendToolResult(
                    sessionId, userId, claimed.runId(), pending.requestId(), rawResult, true
            );
        }

        List<ToolCallResult> toolResults = new ArrayList<>(claimed.toolResults());
        toolResults.add(projectedResult);
        List<String> usedTools = new ArrayList<>(claimed.usedTools());
        usedTools.add(pending.toolName());
        boolean usedRag = claimed.usedRag()
                || (DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH.equals(pending.toolName()) && rawResult.success());
        budget.recordToolCall();
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

        return executeLoop(
                claimed.request(), claimed.runId(), leaseOwnerId, sessionId, userId, budget,
                toolResults, usedTools, usedRag, profile, listener
        );
    }

    private boolean hasCertainPersistedResult(String runId,
                                              ToolCallRequest pending,
                                              ToolExecutionRecord execution) {
        if (pending == null || execution == null || execution.result() == null) {
            return false;
        }
        if (!runId.equals(execution.runId())
                || !pending.requestId().equals(execution.toolCallId())
                || !pending.toolName().equals(execution.toolName())) {
            return false;
        }
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

    private AgentRuntimeResult finishFinalAnswer(AgentRequest request,
                                                 String runId,
                                                 String sessionId,
                                                 String userId,
                                                 String answer,
                                                 List<ToolCallResult> toolResults,
                                                 List<String> usedTools,
                                                 boolean usedRag,
                                                 AgentRunBudget budget,
                                                 AgentEventListener listener) {
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
        if (current != null && isTerminal(current.state())) {
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

    AgentToolCall assignExecutionId(AgentToolCall modelCall) {
        return new AgentToolCall(
                UUID.randomUUID().toString(),
                modelCall.toolName(),
                modelCall.arguments(),
                modelCall.reason()
        );
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
     * 往数据库中添加 agent 事件
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
        try {
            // 这里使用的是空监听器，不会做任何的操作
            listener.onEvent(event);
        }
        catch (RuntimeException ignored) {
            // Event transport failure cannot roll back already persisted Agent execution state.
        }
        return event;
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

    private AgentStopReason inferStoredStopReason(AgentRunRecord stored) {
        return switch (stored.state()) {
            case COMPLETED -> AgentStopReason.COMPLETED;
            case WAITING_APPROVAL -> AgentStopReason.WAITING_APPROVAL;
            case RUNNING -> AgentStopReason.IN_PROGRESS;
            case BLOCKED, REJECTED -> AgentStopReason.GUARDRAIL_BLOCKED;
            case MANUAL_REVIEW -> AgentStopReason.TOOL_ERROR;
            default -> AgentStopReason.INTERNAL_ERROR;
        };
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
