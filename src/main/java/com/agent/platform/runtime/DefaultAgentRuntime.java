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
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.workflow.WorkflowNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 单一模型驱动 Agent Runtime。
 *
 * <p>同步接口与 SSE 接口只需选择不同的 AgentEventListener，执行语义完全一致。
 * 每一次模型调用、工具策略、工具执行和终止原因都先写入数据库事件时间线。</p>
 */
@Service
public class DefaultAgentRuntime implements AgentRuntime {

    private static final String DEFAULT_SESSION_ID = "default-conversation";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final int MAX_RETURNED_EVENTS = 10_000;

    private final AgentProperties properties;
    private final AgentTimelineStore timelineStore;
    private final AgentRunStore runStore;
    private final AgentContextManager contextManager;
    private final AgentModelGateway modelGateway;
    private final AgentCapabilityRegistry capabilityRegistry;
    private final AgentToolRuntime toolRuntime;
    private final GuardrailService guardrailService;
    private final ApprovalService approvalService;
    private final TokenEstimator tokenEstimator;

    private final MemoryService memoryService;

    public DefaultAgentRuntime(AgentProperties properties,
                               AgentTimelineStore timelineStore,
                               AgentRunStore runStore,
                               AgentContextManager contextManager,
                               AgentModelGateway modelGateway,
                               AgentCapabilityRegistry capabilityRegistry,
                               AgentToolRuntime toolRuntime,
                               GuardrailService guardrailService,
                               ApprovalService approvalService,
                               TokenEstimator tokenEstimator,
                               MemoryService memoryService) {
        this.properties = properties;
        this.timelineStore = timelineStore;
        this.runStore = runStore;
        this.contextManager = contextManager;
        this.modelGateway = modelGateway;
        this.capabilityRegistry = capabilityRegistry;
        this.toolRuntime = toolRuntime;
        this.guardrailService = guardrailService;
        this.approvalService = approvalService;
        this.tokenEstimator = tokenEstimator;
        this.memoryService = memoryService;
    }

    @Override
    public AgentRuntimeResult run(AgentRequest originalRequest, AgentEventListener listener) {
        if (originalRequest == null || originalRequest.question() == null || originalRequest.question().isBlank()) {
            throw new IllegalArgumentException("agent request question must not be blank");
        }
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        String sessionId = normalize(originalRequest.conversationId(), DEFAULT_SESSION_ID);
        String userId = normalize(originalRequest.userId(), DEFAULT_USER_ID);
        String runId = UUID.randomUUID().toString();
        timelineStore.openSession(sessionId, userId);
        runStore.create(AgentRunRecord.create(runId, runId, sessionId, originalRequest));
        AgentRunBudget budget = new AgentRunBudget(AgentRunLimits.from(properties));
        publish(sessionId, userId, runId, AgentEventType.RUN_STARTED,
                "agent run started", Map.of("question", originalRequest.question()), effectiveListener);

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
        AgentRequest request = new AgentRequest(sessionId, userId, safeQuestion, originalRequest.metadata());
        if (!safeQuestion.equals(originalRequest.question())) {
            runStore.update(runId, current -> current.withRequest(request));
        }
        timelineStore.appendMessages(sessionId, userId, runId, List.of(
                AgentMessageDraft.user(safeQuestion, tokenEstimator.estimate(safeQuestion))
        ));
        memoryService.rememberLongTerm(sessionId, userId, new MemoryMessage("user", safeQuestion, Instant.now()));
        return executeLoop(request, runId, sessionId, userId, budget,
                new ArrayList<>(), new ArrayList<>(), false, effectiveListener);
    }

    @Override
    public AgentRuntimeResult resume(String runId, AgentEventListener listener) {
        AgentEventListener effectiveListener = listener == null ? AgentEventListener.NOOP : listener;
        AgentRunRecord stored = runStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        if (stored.state() != AgentRunState.WAITING_APPROVAL) {
            return resultFromStored(stored, inferStoredStopReason(stored));
        }
        ApprovalRecord approval = approvalService.find(stored.approvalId())
                .orElseThrow(() -> new IllegalArgumentException("approval not found: " + stored.approvalId()));
        if (approval.status() == ApprovalStatus.REQUESTED) {
            return resultFromStored(stored, AgentStopReason.WAITING_APPROVAL);
        }
        AgentRunRecord claimed = runStore.claimForResume(runId)
                .orElseGet(() -> runStore.find(runId).orElse(stored));
        String sessionId = claimed.conversationId();
        String userId = normalize(claimed.userId(), DEFAULT_USER_ID);
        AgentRunBudget budget = new AgentRunBudget(AgentRunLimits.from(properties));
        List<ToolCallResult> toolResults = new ArrayList<>(claimed.toolResults());
        List<String> usedTools = new ArrayList<>(claimed.usedTools());

        ToolDefinition definition = capabilityRegistry.findCapability(approval.toolCallRequest().toolName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "approved capability no longer exists: " + approval.toolCallRequest().toolName()
                ));
        ToolCallResult result;
        if (approval.status() == ApprovalStatus.REJECTED) {
            result = new ToolCallResult(
                    approval.toolCallRequest().toolName(),
                    false,
                    "",
                    "human approval rejected: " + approval.decisionReason(),
                    Map.of("approvalId", approval.approvalId(), "approvalStatus", approval.status().name())
            );
        }
        else {
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
        appendToolResult(sessionId, userId, runId, approval.toolCallRequest().requestId(), result);
        toolResults.add(result);
        publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                result.success() ? "approved tool completed" : "approved tool returned failure",
                toolResultPayload(result), effectiveListener);
        AgentRequest request = claimed.request();
        return executeLoop(request, runId, sessionId, userId, budget, toolResults, usedTools,
                claimed.usedRag(), effectiveListener);
    }

    private AgentRuntimeResult executeLoop(AgentRequest request,
                                           String runId,
                                           String sessionId,
                                           String userId,
                                           AgentRunBudget budget,
                                           List<ToolCallResult> toolResults,
                                           List<String> usedTools,
                                           boolean usedRag,
                                           AgentEventListener listener) {
        Set<String> toolCallIds = new HashSet<>();
        int contextOverflowRetries = 0;
        while (true) {
            Optional<AgentStopReason> turnStop = budget.beforeTurn();
            if (turnStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, turnStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }
            budget.recordTurnStarted();

            long contextTokenBudget = contextMessageBudget();
            AgentContextView context = contextManager.project(
                    sessionId,
                    userId,
                    request.question(),
                    contextTokenBudget
            );
            if (context.omittedMessages() > 0) {
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

            Optional<AgentStopReason> modelStop = budget.beforeModelCall();
            if (modelStop.isPresent()) {
                return finishBudgetStop(request, runId, sessionId, userId, modelStop.get(),
                        toolResults, usedTools, usedRag, budget, listener);
            }

            publish(sessionId, userId, runId, AgentEventType.MODEL_STARTED,
                    "model turn started", Map.of("turn", budget.snapshot().turns()), listener);
            AgentModelTurn modelTurn;
            while (true) {
                try {
                    modelTurn = modelGateway.nextTurn(new AgentModelRequest(
                            runId,
                            sessionId,
                            properties.getDefaultSystemPrompt(),
                            context.messages(),
                            capabilityRegistry.listCapabilities(),
                            request.metadata()
                    ));
                    break;
                }
                catch (RuntimeException modelFailure) {
                    if (isContextOverflow(modelFailure)
                            && contextOverflowRetries < Math.max(0, properties.getMaxContextOverflowRetries())) {
                        contextOverflowRetries++;
                        budget.recordModelCall(new LlmUsage(0, 0, 0, 0, 0, "", "context-overflow"), 0);
                        Optional<AgentStopReason> retryStop = budget.beforeModelCall();
                        if (retryStop.isPresent()) {
                            return finishBudgetStop(request, runId, sessionId, userId, retryStop.get(),
                                    toolResults, usedTools, usedRag, budget, listener);
                        }
                        long retryBudget = Math.max(1, contextTokenBudget / 2);
                        context = contextManager.compact(
                                sessionId, userId, runId, request.question(), retryBudget,
                                "provider_context_overflow"
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
                    AgentStopReason stopReason = isContextOverflow(modelFailure)
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
            LlmUsage effectiveUsage = effectiveUsage(modelTurn, context);
            budget.recordModelCall(effectiveUsage, 0);
            publish(sessionId, userId, runId, AgentEventType.MODEL_COMPLETED,
                    "model turn completed",
                    Map.of(
                            "finishReason", modelTurn.finishReason(),
                            "toolCallCount", modelTurn.toolCalls().size(),
                            "promptTokens", effectiveUsage.promptTokens(),
                            "completionTokens", effectiveUsage.completionTokens(),
                            "usageSource", effectiveUsage.source()
                    ),
                    listener);

            if (!modelTurn.hasToolCalls()) {
                return finishFinalAnswer(request, runId, sessionId, userId, modelTurn.assistantText(),
                        toolResults, usedTools, usedRag, budget, listener);
            }

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
                Optional<AgentStopReason> toolStop = budget.beforeToolCall();
                if (toolStop.isPresent()) {
                    return finishBudgetStop(request, runId, sessionId, userId, toolStop.get(),
                            toolResults, usedTools, usedRag, budget, listener);
                }
                AgentToolCall call = uniqueToolCall(rawCall, toolCallIds);
                timelineStore.appendMessages(sessionId, userId, runId, List.of(
                        AgentMessageDraft.toolCall(
                                call.toolCallId(),
                                call.toolName(),
                                call.arguments(),
                                Map.of("reason", call.reason()),
                                tokenEstimator.estimate(String.valueOf(call.arguments()))
                        )
                ));
                publish(sessionId, userId, runId, AgentEventType.TOOL_REQUESTED,
                        "model requested capability",
                        Map.of("toolCallId", call.toolCallId(), "toolName", call.toolName(), "arguments", call.arguments()),
                        listener);

                Optional<ToolDefinition> definition = capabilityRegistry.findCapability(call.toolName());
                if (definition.isEmpty()) {
                    ToolCallResult unknown = new ToolCallResult(
                            call.toolName(), false, "", "unknown capability: " + call.toolName(),
                            Map.of("errorType", "UNKNOWN_CAPABILITY")
                    );
                    appendToolResult(sessionId, userId, runId, call.toolCallId(), unknown);
                    toolResults.add(unknown);
                    budget.recordToolCall();
                    publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                            "unknown capability returned to model", toolResultPayload(unknown), listener);
                    continue;
                }

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
                    List<ToolCallResult> persistedToolResults = List.copyOf(toolResults);
                    List<String> persistedUsedTools = List.copyOf(usedTools);
                    boolean ragUsedAtPause = usedRag;
                    runStore.update(runId, current -> current.waitingForApproval(
                            execution.approvalId(),
                            execution.request(),
                            persistedToolResults,
                            persistedUsedTools,
                            ragUsedAtPause
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
                appendToolResult(sessionId, userId, runId, call.toolCallId(), toolResult);
                toolResults.add(toolResult);
                usedTools.add(call.toolName());
                if (DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH.equals(call.toolName()) && toolResult.success()) {
                    usedRag = true;
                }
                publish(sessionId, userId, runId, AgentEventType.TOOL_COMPLETED,
                        toolResult.success() ? "capability completed" : "capability failure returned to model",
                        toolResultPayload(toolResult), listener);
            }
        }
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
        WorkflowNode node = switch (state) {
            case COMPLETED -> WorkflowNode.FINISH;
            case BLOCKED, REJECTED -> WorkflowNode.BLOCKED;
            case WAITING_APPROVAL -> WorkflowNode.TOOL_APPROVAL;
            default -> WorkflowNode.FAILED;
        };
        runStore.update(runId, current -> current.finished(
                state,
                node,
                answer,
                state == AgentRunState.COMPLETED ? "" : stopReason.name(),
                toolResults,
                usedTools,
                usedRag,
                guardrailBlocked
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

    private void appendToolResult(String sessionId,
                                  String userId,
                                  String runId,
                                  String toolCallId,
                                  ToolCallResult result) {
        timelineStore.appendMessages(sessionId, userId, runId, List.of(
                AgentMessageDraft.toolResult(
                        toolCallId,
                        result.toolName(),
                        result.success(),
                        result.content(),
                        result.errorMessage(),
                        result.metadata(),
                        tokenEstimator.estimate(result.content()) + tokenEstimator.estimate(result.errorMessage())
                )
        ));
    }

    private AgentToolCall uniqueToolCall(AgentToolCall call, Set<String> usedIds) {
        if (usedIds.add(call.toolCallId())) {
            return call;
        }
        String replacement = UUID.randomUUID().toString();
        usedIds.add(replacement);
        return new AgentToolCall(replacement, call.toolName(), call.arguments(),
                call.reason() + " [runtime replaced duplicate id " + call.toolCallId() + "]");
    }

    private LlmUsage effectiveUsage(AgentModelTurn turn, AgentContextView context) {
        if (turn.usage() != null && turn.usage().hasTokenUsage()) {
            return turn.usage();
        }
        long output = tokenEstimator.estimate(turn.rawResponse());
        return new LlmUsage(
                context.estimatedTokens(),
                output,
                context.estimatedTokens() + output,
                0,
                0,
                turn.usage() == null ? "" : turn.usage().model(),
                "runtime-estimate"
        );
    }

    private long contextMessageBudget() {
        long staticTokens = tokenEstimator.estimate(properties.getDefaultSystemPrompt()) + 700;
        for (ToolDefinition definition : capabilityRegistry.listCapabilities()) {
            staticTokens += tokenEstimator.estimate(definition.name())
                    + tokenEstimator.estimate(definition.description())
                    + tokenEstimator.estimate(definition.inputSchema());
        }
        long availableWindow = Math.max(1, properties.getModelContextWindowTokens())
                - Math.max(0, properties.getContextOutputReserveTokens())
                - Math.max(0, properties.getContextSafetyMarginTokens())
                - staticTokens;
        return Math.max(1, Math.min(properties.getMaxInputTokensPerRun(), availableWindow));
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

    private AgentEvent publish(String sessionId,
                               String userId,
                               String runId,
                               AgentEventType type,
                               String content,
                               Map<String, Object> payload,
                               AgentEventListener listener) {
        AgentEvent event = timelineStore.appendEvent(
                sessionId,
                userId,
                runId,
                new AgentEventDraft(type, content, payload)
        );
        try {
            listener.onEvent(event);
        }
        catch (RuntimeException ignored) {
            // Event transport failure cannot roll back already persisted Agent execution state.
        }
        return event;
    }

    private Map<String, Object> toolResultPayload(ToolCallResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
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
                "cancelled", budget.cancelled()
        );
    }

    private AgentRuntimeResult resultFromStored(AgentRunRecord stored, AgentStopReason stopReason) {
        AgentRunBudgetSnapshot budget = new AgentRunBudget(AgentRunLimits.from(properties)).snapshot();
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
            case BLOCKED, REJECTED -> AgentStopReason.GUARDRAIL_BLOCKED;
            case MANUAL_REVIEW -> AgentStopReason.TOOL_ERROR;
            default -> AgentStopReason.INTERNAL_ERROR;
        };
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
