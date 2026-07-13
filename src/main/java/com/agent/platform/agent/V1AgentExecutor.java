package com.agent.platform.agent;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.eval.AgentRunEvalEvent;
import com.agent.platform.eval.EvalEventRecorder;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.llm.LlmCallException;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.prompt.PromptAssembler;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.query.QueryRewriteService;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.InMemoryAgentRuntimeStore;
import com.agent.platform.runtime.ToolExecutionClaim;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentRouter;
import com.agent.platform.router.IntentType;
import com.agent.platform.skill.SkillDefinition;
import com.agent.platform.skill.SkillSelector;
import com.agent.platform.tool.ToolCallPlan;
import com.agent.platform.tool.ToolCallPlanner;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.trace.TraceContext;
import com.agent.platform.trace.TraceRecorder;
import com.agent.platform.trace.TraceSpanKind;
import com.agent.platform.trace.TraceSpanStatus;
import com.agent.platform.trace.TraceSummary;
import com.agent.platform.workflow.WorkflowCheckpoint;
import com.agent.platform.workflow.WorkflowExecutionPlan;
import com.agent.platform.workflow.WorkflowNode;
import com.agent.platform.workflow.WorkflowPlanner;
import com.agent.platform.workflow.WorkflowRecorder;
import com.agent.platform.workflow.WorkflowRunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Deprecated(forRemoval = false)
@Service
public class V1AgentExecutor implements AgentExecutor {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private static final Pattern DURATION_PATTERN = Pattern.compile("durationMs=(\\d+)");

    private final AgentProperties agentProperties;

    private final TraceRecorder traceRecorder;

    private final MemoryService memoryService;

    private final GuardrailService guardrailService;

    private final IntentRouter intentRouter;

    private final SkillSelector skillSelector;

    private final QueryRewriteService queryRewriteService;

    private final RagService ragService;

    private final ToolRegistry toolRegistry;

    private final ToolCallPlanner toolCallPlanner;

    private final ToolExecutor toolExecutor;

    private final ApprovalService approvalService;

    private final PromptAssembler promptAssembler;

    private final LlmService llmService;

    private final EvalEventRecorder evalEventRecorder;

    private final WorkflowPlanner workflowPlanner;

    private final WorkflowRecorder workflowRecorder;

    private final AgentRunStore agentRunStore;

    private final ToolExecutionStore toolExecutionStore;

    @Autowired
    public V1AgentExecutor(AgentProperties agentProperties,
                           TraceRecorder traceRecorder,
                           MemoryService memoryService,
                           GuardrailService guardrailService,
                           IntentRouter intentRouter,
                           SkillSelector skillSelector,
                           QueryRewriteService queryRewriteService,
                           RagService ragService,
                           ToolRegistry toolRegistry,
                           ToolCallPlanner toolCallPlanner,
                           ToolExecutor toolExecutor,
                           ApprovalService approvalService,
                           PromptAssembler promptAssembler,
                           LlmService llmService,
                           EvalEventRecorder evalEventRecorder,
                           WorkflowPlanner workflowPlanner,
                           WorkflowRecorder workflowRecorder,
                           AgentRunStore agentRunStore,
                           ToolExecutionStore toolExecutionStore) {
        this.agentProperties = agentProperties;
        this.traceRecorder = traceRecorder;
        this.memoryService = memoryService;
        this.guardrailService = guardrailService;
        this.intentRouter = intentRouter;
        this.skillSelector = skillSelector;
        this.queryRewriteService = queryRewriteService;
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
        this.toolCallPlanner = toolCallPlanner;
        this.toolExecutor = toolExecutor;
        this.approvalService = approvalService;
        this.promptAssembler = promptAssembler;
        this.llmService = llmService;
        this.evalEventRecorder = evalEventRecorder;
        this.workflowPlanner = workflowPlanner;
        this.workflowRecorder = workflowRecorder;
        this.agentRunStore = agentRunStore;
        this.toolExecutionStore = toolExecutionStore;
    }

    public V1AgentExecutor(AgentProperties agentProperties,
                           TraceRecorder traceRecorder,
                           MemoryService memoryService,
                           GuardrailService guardrailService,
                           IntentRouter intentRouter,
                           SkillSelector skillSelector,
                           QueryRewriteService queryRewriteService,
                           RagService ragService,
                           ToolRegistry toolRegistry,
                           ToolCallPlanner toolCallPlanner,
                           ToolExecutor toolExecutor,
                           ApprovalService approvalService,
                           PromptAssembler promptAssembler,
                           LlmService llmService,
                           EvalEventRecorder evalEventRecorder,
                           WorkflowPlanner workflowPlanner,
                           WorkflowRecorder workflowRecorder) {
        this(
                agentProperties,
                traceRecorder,
                memoryService,
                guardrailService,
                intentRouter,
                skillSelector,
                queryRewriteService,
                ragService,
                toolRegistry,
                toolCallPlanner,
                toolExecutor,
                approvalService,
                promptAssembler,
                llmService,
                evalEventRecorder,
                workflowPlanner,
                workflowRecorder,
                new InMemoryAgentRuntimeStore(),
                new InMemoryAgentRuntimeStore()
        );
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        // 阶段 1：初始化本次 Agent Run。
        // conversationId 标识多轮会话；runId/traceId 标识当前这一次执行。
        String conversationId = normalizeConversationId(request.conversationId());
        TraceContext trace = traceRecorder.start(conversationId, request.question());
        String runId = trace.traceId();

        // 先持久化 RUNNING 状态。后续即使进入澄清、阻断、审批或失败分支，
        // 都可以通过同一个 runId 查询这次执行留下的状态和上下文。
        agentRunStore.create(AgentRunRecord.create(runId, trace.traceId(), conversationId, request));

        // steps 面向接口调用方展示执行过程；其余字段会在 finish() 中进入 Trace/Eval。
        List<AgentStep> steps = new ArrayList<>();
        List<String> usedTools = new ArrayList<>();
        boolean usedRag = false;
        boolean blockedByGuardrail = false;

        try {
            // 阶段 2：加载当前会话的短期消息、摘要、长期记忆和用户画像。
            // question 也会作为长期记忆语义召回的查询条件。
            ConversationMemory memory = memoryService.load(conversationId, request.userId(), request.question());
            addStep(trace, steps, "memory.load", "COMPLETED",
                    "messages=" + memory.messages().size()
                            + ", longTerm=" + memory.longTermMemories().size()
                            + ", profileItems=" + memory.userProfile().items().size()
                            + ", recalled=" + memory.recalledMemories().size());

            // 阶段 3：输入护栏必须在问题进入 Memory、Router、Tool 和 LLM 之前执行。
            GuardrailDecision inputDecision = guardrailService.checkInput(request.question());
            addStep(trace, steps, "guardrail.input", inputDecision.action().name(), inputDecision.reason());
            if (inputDecision.action() == GuardrailAction.BLOCK) {
                // Prompt Injection 或敏感信息外泄等高风险输入直接结束，不再调用模型和工具。
                blockedByGuardrail = true;
                return finishBlocked(request, conversationId, trace, steps, usedTools, usedRag, true,
                        "请求被输入护栏拦截：" + inputDecision.reason());
            }
            if (inputDecision.action() == GuardrailAction.REDACT && inputDecision.safeContent() != null) {
                // REDACT 不终止任务，而是使用脱敏后的安全内容替换原问题。
                // 同时更新 AgentRun，防止恢复执行时重新使用未脱敏的原始输入。
                request = new AgentRequest(request.conversationId(), request.userId(), inputDecision.safeContent(), request.metadata());
                AgentRequest safeRequest = request;
                agentRunStore.update(runId, current -> current.withRequest(safeRequest));
                addStep(trace, steps, "guardrail.input.redact", "COMPLETED", "input was redacted before memory and model usage");
            }

            // 护栏处理完成后，才把本轮用户问题保存到会话记忆中。
            memoryService.append(conversationId, request.userId(), new MemoryMessage("user", request.question(), Instant.now()));

            // 阶段 4：Skill 表示适合完成当前任务的能力描述及其绑定工具。
            // 没有匹配 Skill 也可以继续执行，只是在 Trace 中记录为 NONE。
            Optional<SkillDefinition> skill = skillSelector.select(request, memory);
            addStep(trace, steps, "skill.select", skill.map(SkillDefinition::name).orElse("NONE"), skill.map(SkillDefinition::description).orElse("no skill selected"));

            // 阶段 5：Router 决定本次请求走 CHAT、RAG、TOOL 还是 CLARIFY。
            IntentRoute route = intentRouter.route(request, memory);
            addStep(trace, steps, "intent.route", route.type().name(), route.reason());

            // 根据路由生成显式 Workflow 计划并持久化。
            // 前面的 Memory/Guardrail/Route 在计划创建前已经完成，因此需要补写已有 checkpoint。
            WorkflowExecutionPlan workflowPlan = workflowPlanner.plan(trace.traceId(), conversationId, route);
            workflowRecorder.start(workflowPlan);
            persistExistingWorkflowCheckpoints(trace, steps);
            agentRunStore.update(runId, current -> current.withPlan(workflowPlan));
            addStep(trace, steps, "workflow.plan", "COMPLETED",
                    "route=" + route.type().name() + ", nodes=" + workflowPlan.nodes().size() + ", resumable=" + workflowPlan.resumable());
            if (route.type() == IntentType.CLARIFY) {
                // 信息不足时提前返回澄清提示，不浪费 RAG、Tool 和 LLM 调用。
                String answer = "你的问题还不够明确。请补充工单编号、故障现象，或者说明你想查询哪类知识库资料。";
                memoryService.append(conversationId, request.userId(), new MemoryMessage("assistant", answer, Instant.now()));
                return finish(request, conversationId, AgentRunStatus.NEEDS_CLARIFICATION, answer, steps, trace, usedTools, usedRag, false);
            }

            // 阶段 6：结合历史上下文，把省略主语或依赖上文的问题改写成可独立理解的查询。
            String rewrittenQuery = queryRewriteService.rewrite(request, memory);
            addStep(trace, steps, "query.rewrite", "COMPLETED", rewrittenQuery);

            // 三种路由最终都汇合到 PromptAssembler，因此先准备空的 RAG/Tool 结果。
            RagResult ragResult = RagResult.empty(rewrittenQuery);
            List<ToolCallResult> toolResults = new ArrayList<>();

            if (route.type() == IntentType.RAG) {
                // RAG 路由：执行混合检索和 Rerank，结果稍后作为 Prompt 上下文。
                long ragStartNanos = System.nanoTime();
                ragResult = ragService.retrieve(rewrittenQuery, 3);
                usedRag = !ragResult.documents().isEmpty();
                addStep(trace, steps, "rag.retrieve", ragResult.enoughEvidence() ? "HIT" : "MISS",
                        "mode=" + ragResult.retrievalMode()
                                + ", documents=" + ragResult.documents().size()
                                + ", topK=" + ragResult.effectiveTopK()
                                + ", minSimilarity=" + ragResult.minSimilarity()
                                + ", durationMs=" + elapsedMs(ragStartNanos)
                                + ", hits=" + ragHitSummary(ragResult));
            }
            else if (route.type() == IntentType.TOOL) {
                // TOOL 路由：进入“规划 -> 护栏/审批 -> 执行 -> 失败重规划”的有限循环。
                ToolExecutionOutcome outcome = executeToolBranch(runId, request, memory, conversationId, route, trace, steps);
                usedTools.addAll(outcome.usedTools());
                toolResults.addAll(outcome.toolResults());
                if (outcome.terminalStatus() != null) {
                    // WAITING_APPROVAL、MANUAL_REVIEW 等状态会暂停或终止当前 Run，
                    // 此时不能继续组装 Prompt 和调用 LLM。
                    blockedByGuardrail = outcome.blockedByGuardrail();
                    return finish(
                            request,
                            conversationId,
                            outcome.terminalStatus(),
                            outcome.terminalAnswer(),
                            steps,
                            trace,
                            usedTools,
                            false,
                            blockedByGuardrail
                    );
                }
                if (outcome.blockedAnswer() != null) {
                    // 工具不存在、规划失败或工具护栏阻断时，统一按 BLOCKED 收口。
                    blockedByGuardrail = outcome.blockedByGuardrail();
                    return finishBlocked(request, conversationId, trace, steps, usedTools, false, blockedByGuardrail, outcome.blockedAnswer());
                }

                // 工具执行完成后保存中间结果。后面如果 LLM 失败，仍能查询工具执行证据。
                List<ToolCallResult> persistedToolResults = List.copyOf(toolResults);
                List<String> persistedUsedTools = List.copyOf(usedTools);
                agentRunStore.update(runId, current -> current.finished(
                        AgentRunState.RUNNING,
                        WorkflowNode.TOOL_EXECUTE,
                        "",
                        "",
                        persistedToolResults,
                        persistedUsedTools,
                        false,
                        false
                ));
            }
            else {
                // CHAT 路由不检索资料、不调用工具，直接使用用户问题和 Memory 组装 Prompt。
                addStep(trace, steps, "chat.fallback", "COMPLETED", "general chat uses prompt without RAG or tool");
            }

            // 阶段 7：把 System Prompt、用户问题、Memory、RAG 证据和工具结果统一组装。
            PromptRequest prompt = promptAssembler.assemble(request, memory, ragResult, toolResults);
            addStep(trace, steps, "prompt.assemble", "COMPLETED", "contextBlocks=" + prompt.contextBlocks().size());

            // 阶段 8：调用真实模型。LlmService 内部负责超时、重试和降级，
            // 编排层只处理成功结果或包装后的 LlmCallException。
            String answer;
            long llmStartNanos = System.nanoTime();
            try {
                answer = llmService.complete(prompt);
                long llmDurationMs = elapsedMs(llmStartNanos);
                addStep(trace, steps, "llm.call", "COMPLETED",
                        "real llm generated answer, durationMs=" + llmDurationMs, llmDurationMs);
                recordUsage(trace, prompt, answer);
            }
            catch (LlmCallException exception) {
                long llmDurationMs = elapsedMs(llmStartNanos);
                addStep(trace, steps, "llm.call", "FAILED",
                        "errorType=" + exception.errorType() + ", durationMs=" + llmDurationMs, llmDurationMs);
                return finish(request, conversationId, AgentRunStatus.FAILED, exception.safeMessage(), steps, trace, usedTools, usedRag, blockedByGuardrail);
            }

            // 阶段 9：模型回答返回后再做一次输出护栏，防止敏感信息或违规内容直接返回。
            GuardrailDecision outputDecision = guardrailService.checkOutput(answer);
            addStep(trace, steps, "guardrail.output", outputDecision.action().name(), outputDecision.reason());
            if (outputDecision.action() == GuardrailAction.REDACT) {
                answer = outputDecision.safeContent();
            }
            else if (outputDecision.action() == GuardrailAction.BLOCK) {
                blockedByGuardrail = true;
                return finishBlocked(request, conversationId, trace, steps, usedTools, usedRag, true,
                        "回答被输出护栏拦截：" + outputDecision.reason());
            }

            // 阶段 10：保存本轮 assistant 消息，随后统一结束 Run、Trace、Workflow 和 Eval。
            memoryService.append(conversationId, request.userId(), new MemoryMessage("assistant", answer, Instant.now()));
            addStep(trace, steps, "conversation.save", "COMPLETED", "conversation messages appended");
            return finish(request, conversationId, AgentRunStatus.COMPLETED, answer, steps, trace, usedTools, usedRag, blockedByGuardrail);
        }
        catch (RuntimeException exception) {
            // 未被业务分支单独处理的异常在最外层兜底，避免把堆栈和内部细节直接暴露给调用方。
            addStep(trace, steps, "agent.error", "FAILED", "errorType=" + exception.getClass().getSimpleName());
            return finish(request, conversationId, AgentRunStatus.FAILED, "Agent 执行失败，请稍后重试或根据 traceId 排查。", steps, trace, usedTools, usedRag, blockedByGuardrail);
        }
    }

    @Override
    public AgentResponse resume(String runId) {
        AgentRunRecord current = agentRunStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run not found: " + runId));
        if (current.state() != AgentRunState.WAITING_APPROVAL) {
            return storedResponse(current, "agent run is not waiting for approval");
        }

        ApprovalRecord approval = approvalService.find(current.approvalId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "approval not found for agent run: " + current.approvalId()
                ));
        if (approval.status() == ApprovalStatus.REQUESTED) {
            return storedResponse(current, "agent run is still waiting for human approval");
        }

        if (approval.status() == ApprovalStatus.REJECTED || approval.status() == ApprovalStatus.EXPIRED) {
            TraceContext trace = traceRecorder.resume(current.traceId());
            List<AgentStep> steps = new ArrayList<>();
            addStep(trace, steps, "approval.decision", approval.status().name(), approval.decisionReason());
            String answer = approval.status() == ApprovalStatus.REJECTED
                    ? "人工审批已拒绝，高风险工具未执行。"
                    : "人工审批已过期，高风险工具未执行。";
            memoryService.append(
                    current.conversationId(),
                    current.userId(),
                    new MemoryMessage("assistant", answer, Instant.now())
            );
            return finish(
                    current.request(),
                    current.conversationId(),
                    AgentRunStatus.REJECTED,
                    answer,
                    steps,
                    trace,
                    current.usedTools(),
                    current.usedRag(),
                    false
            );
        }

        AgentRunRecord claimed = agentRunStore.claimForResume(runId).orElse(null);
        if (claimed == null) {
            AgentRunRecord latest = agentRunStore.find(runId).orElse(current);
            return storedResponse(latest, "another worker already claimed this agent run");
        }

        TraceContext trace = traceRecorder.resume(claimed.traceId());
        List<AgentStep> steps = new ArrayList<>();
        workflowRecorder.finish(runId, WorkflowRunStatus.RUNNING, "");
        addStep(trace, steps, "workflow.resume", "RUNNING",
                "resumeCount=" + claimed.resumeCount() + ", from=" + WorkflowNode.TOOL_APPROVAL);
        addStep(trace, steps, "approval.decision", "APPROVED", approval.decisionReason());

        ToolCallRequest pendingToolCall = claimed.pendingToolCall();
        if (pendingToolCall == null) {
            return finish(
                    claimed.request(),
                    claimed.conversationId(),
                    AgentRunStatus.MANUAL_REVIEW,
                    "恢复上下文缺少 pendingToolCall，请人工核对。",
                    steps,
                    trace,
                    claimed.usedTools(),
                    claimed.usedRag(),
                    false
            );
        }

        IdempotentToolOutcome execution = executeToolWithRetry(runId, pendingToolCall, trace, steps);
        if (execution.manualReview()) {
            return finish(
                    claimed.request(),
                    claimed.conversationId(),
                    AgentRunStatus.MANUAL_REVIEW,
                    execution.reason(),
                    steps,
                    trace,
                    claimed.usedTools(),
                    claimed.usedRag(),
                    false
            );
        }

        ToolCallResult toolResult = execution.result();
        List<ToolCallResult> toolResults = new ArrayList<>(claimed.toolResults());
        toolResults.add(toolResult);
        List<String> usedTools = new ArrayList<>(claimed.usedTools());
        if (!usedTools.contains(pendingToolCall.toolName())) {
            usedTools.add(pendingToolCall.toolName());
        }
        List<ToolCallResult> persistedResults = List.copyOf(toolResults);
        List<String> persistedTools = List.copyOf(usedTools);
        agentRunStore.update(runId, run -> run.finished(
                AgentRunState.RUNNING,
                WorkflowNode.TOOL_EXECUTE,
                "",
                "",
                persistedResults,
                persistedTools,
                run.usedRag(),
                false
        ));

        if (!toolResult.success()) {
            return finish(
                    claimed.request(),
                    claimed.conversationId(),
                    AgentRunStatus.FAILED,
                    "高风险工具执行失败：" + toolResult.errorMessage(),
                    steps,
                    trace,
                    persistedTools,
                    claimed.usedRag(),
                    false
            );
        }

        AgentRequest request = claimed.request();
        ConversationMemory memory = memoryService.load(
                claimed.conversationId(),
                claimed.userId(),
                request.question()
        );
        PromptRequest prompt = promptAssembler.assemble(
                request,
                memory,
                RagResult.empty(request.question()),
                persistedResults
        );
        addStep(trace, steps, "prompt.assemble", "COMPLETED",
                "resumed contextBlocks=" + prompt.contextBlocks().size());

        String answer;
        long llmStartNanos = System.nanoTime();
        try {
            answer = llmService.complete(prompt);
            long durationMs = elapsedMs(llmStartNanos);
            addStep(trace, steps, "llm.call", "COMPLETED",
                    "resumed llm generated answer, durationMs=" + durationMs, durationMs);
            recordUsage(trace, prompt, answer);
        }
        catch (LlmCallException exception) {
            long durationMs = elapsedMs(llmStartNanos);
            addStep(trace, steps, "llm.call", "FAILED",
                    "errorType=" + exception.errorType() + ", durationMs=" + durationMs, durationMs);
            return finish(
                    request,
                    claimed.conversationId(),
                    AgentRunStatus.FAILED,
                    exception.safeMessage(),
                    steps,
                    trace,
                    persistedTools,
                    claimed.usedRag(),
                    false
            );
        }

        GuardrailDecision outputDecision = guardrailService.checkOutput(answer);
        addStep(trace, steps, "guardrail.output", outputDecision.action().name(), outputDecision.reason());
        if (outputDecision.action() == GuardrailAction.REDACT) {
            answer = outputDecision.safeContent();
        }
        else if (outputDecision.action() == GuardrailAction.BLOCK) {
            return finish(
                    request,
                    claimed.conversationId(),
                    AgentRunStatus.BLOCKED,
                    "回答被输出护栏拦截：" + outputDecision.reason(),
                    steps,
                    trace,
                    persistedTools,
                    claimed.usedRag(),
                    true
            );
        }

        memoryService.append(
                claimed.conversationId(),
                claimed.userId(),
                new MemoryMessage("assistant", answer, Instant.now())
        );
        addStep(trace, steps, "conversation.save", "COMPLETED", "resumed conversation appended");
        return finish(
                request,
                claimed.conversationId(),
                AgentRunStatus.COMPLETED,
                answer,
                steps,
                trace,
                persistedTools,
                claimed.usedRag(),
                false
        );
    }

    private AgentResponse storedResponse(AgentRunRecord record, String reason) {
        TraceSummary summary = traceRecorder.findRun(record.traceId())
                .map(run -> new TraceSummary(run.traceId(), run.conversationId(), run.events()))
                .orElseGet(() -> new TraceSummary(record.traceId(), record.conversationId(), List.of()));
        AgentRunStatus status = switch (record.state()) {
            case CREATED, RUNNING -> AgentRunStatus.RUNNING;
            case WAITING_APPROVAL -> AgentRunStatus.WAITING_APPROVAL;
            case COMPLETED -> AgentRunStatus.COMPLETED;
            case NEEDS_CLARIFICATION -> AgentRunStatus.NEEDS_CLARIFICATION;
            case BLOCKED -> AgentRunStatus.BLOCKED;
            case FAILED -> AgentRunStatus.FAILED;
            case REJECTED -> AgentRunStatus.REJECTED;
            case MANUAL_REVIEW -> AgentRunStatus.MANUAL_REVIEW;
        };
        String answer = record.answer().isBlank() ? reason : record.answer();
        return new AgentResponse(
                record.runId(),
                record.conversationId(),
                status,
                answer,
                record.approvalId(),
                List.of(new AgentStep("run.resume", status.name(), reason)),
                summary
        );
    }

    private ToolExecutionOutcome executeToolBranch(String runId,
                                                   AgentRequest request,
                                                   ConversationMemory memory,
                                                   String conversationId,
                                                   IntentRoute route,
                                                    TraceContext trace,
                                                    List<AgentStep> steps) {
        // ToolRegistry 对本地工具和 MCP 工具提供统一视图，Planner 不需要关心工具来源。
        List<ToolDefinition> availableTools = toolRegistry.listTools();
        addStep(trace, steps, "tool.registry", "COMPLETED", "availableTools=" + availableTools.stream().map(ToolDefinition::name).toList());
        if (availableTools.isEmpty()) {
            return ToolExecutionOutcome.blocked("当前没有可用工具，无法执行该任务。", true);
        }

        List<String> usedTools = new ArrayList<>();
        List<ToolCallResult> toolResults = new ArrayList<>();
        Set<String> executedSignatures = new HashSet<>();
        int maxCalls = Math.max(1, agentProperties.getMaxToolCallsPerRun());

        // 这是一个有最大次数限制的轻量 ReAct/Tool 循环。
        // 每轮 Planner 都能看到之前的 toolResults，从而决定停止、继续或改用其他工具。
        for (int index = 0; index < maxCalls; index++) {
            // 让 LLM 根据情况选择工具
            ToolCallPlan plan = toolCallPlanner.plan(request, memory, route, availableTools, toolResults);
            if (!plan.shouldCallTool()) {
                addStep(trace, steps, "tool.plan", "STOPPED", plan.reason());
                if (toolResults.isEmpty()) {
                    return ToolExecutionOutcome.blocked("未能生成可执行工具调用计划：" + plan.reason(), true);
                }
                break;
            }
            // 根据工具名称寻找工具
            Optional<ToolDefinition> definition = toolRegistry.findTool(plan.toolName());
            if (definition.isEmpty()) {
                addStep(trace, steps, "tool.registry", "FAILED", "tool not found: " + plan.toolName());
                return ToolExecutionOutcome.blocked("没有找到可执行工具：" + plan.toolName(), true);
            }

            ToolCallRequest toolCall = new ToolCallRequest(plan.toolName(), UUID.randomUUID().toString(), plan.arguments());
            String signature = toolCall.toolName() + toolCall.arguments();
            // 同一次 Run 中阻止相同工具和相同参数的循环调用。
            if (!executedSignatures.add(signature)) {
                addStep(trace, steps, "tool.plan", "STOPPED", "duplicate tool call stopped: " + signature);
                break;
            }
            addStep(trace, steps, "tool.plan", "COMPLETED",
                    "planner=" + plan.planner()
                            + ", confidence=" + String.format(Locale.ROOT, "%.2f", plan.confidence())
                            + ", tool=" + toolCall.toolName()
                            + ", args=" + toolCall.arguments()
                            + ", reason=" + plan.reason());

            // 工具执行前单独检查权限和风险级别；高风险副作用不能直接执行。
            GuardrailDecision toolDecision = guardrailService.checkToolCall(definition.get(), toolCall);
            addStep(trace, steps, "guardrail.tool", toolDecision.action().name(), toolDecision.reason());
            if (toolDecision.action() == GuardrailAction.BLOCK) {
                return ToolExecutionOutcome.blocked("工具调用被护栏拦截：" + toolDecision.reason(), true);
            }
            if (toolDecision.action() == GuardrailAction.REQUIRE_APPROVAL) {
                ApprovalRequest approvalRequest = new ApprovalRequest(
                        UUID.randomUUID().toString(),
                        runId,
                        conversationId,
                        toolCall,
                        toolDecision.reason(),
                        Instant.now()
                );
                ApprovalDecision approvalDecision = approvalService.requestApproval(approvalRequest);
                addStep(trace, steps, "approval.request", approvalDecision.status().name(), approvalDecision.reason());
                if (approvalDecision.pending()) {
                    // 只持久化待执行 ToolCall 和当前上下文，不在审批请求阶段执行任何副作用。
                    // 审批通过后由 resume(runId) 使用同一个 toolCallId 恢复。
                    List<ToolCallResult> persistedResults = List.copyOf(toolResults);
                    List<String> persistedTools = List.copyOf(usedTools);
                    agentRunStore.update(runId, current -> current.waitingForApproval(
                            approvalDecision.approvalId(),
                            toolCall,
                            persistedResults,
                            persistedTools,
                            false
                    ));
                    return ToolExecutionOutcome.waitingApproval(
                            persistedTools,
                            persistedResults,
                            approvalDecision.approvalId(),
                            "高风险工具等待人工审批，approvalId=" + approvalDecision.approvalId()
                    );
                }
                if (!approvalDecision.approved()) {
                    return ToolExecutionOutcome.blocked("高风险工具未通过人工审批确认，已停止执行。", false);
                }
            }

            // 审批和护栏通过后，进入带幂等保护和有限重试的真实工具执行。
            IdempotentToolOutcome execution = executeToolWithRetry(runId, toolCall, trace, steps);
            if (execution.manualReview()) {
                return ToolExecutionOutcome.manualReview(
                        usedTools,
                        toolResults,
                        execution.reason()
                );
            }
            ToolCallResult result = execution.result();
            usedTools.add(toolCall.toolName());
            toolResults.add(result);
            if (!result.success()) {
                if (agentProperties.isReplanAfterToolFailure() && index < maxCalls - 1) {
                    addStep(trace, steps, "tool.replan", "READY",
                            "tool failed; previous result will be sent back to planner for a new plan, tool="
                                    + toolCall.toolName() + ", error=" + result.errorMessage());
                    continue;
                }
                break;
            }
        }
        return ToolExecutionOutcome.completed(usedTools, toolResults);
    }

    private IdempotentToolOutcome executeToolWithRetry(String runId,
                                                       ToolCallRequest toolCall,
                                                       TraceContext trace,
                                                       List<AgentStep> steps) {
        // 只对明确可重试的临时错误重试；参数错误、工具不存在等确定性错误不会重试。
        int maxAttempts = Math.max(1, agentProperties.getMaxToolExecutionAttempts());
        ToolCallResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long toolStartNanos = System.nanoTime();
            IdempotentToolOutcome execution = executeToolOnce(runId, toolCall);
            if (execution.manualReview()) {
                addStep(trace, steps, "tool.execute", "MANUAL_REVIEW",
                        "toolCallId=" + toolCall.requestId() + ", reason=" + execution.reason());
                return execution;
            }
            ToolCallResult result = execution.result();
            lastResult = result;
            boolean retryable = !result.success() && retryableToolFailure(result);
            long toolDurationMs = elapsedMs(toolStartNanos);
            String detail = result.success() ? result.content() : result.errorMessage();
            addStep(trace, steps, "tool.execute", result.success() ? "COMPLETED" : "FAILED",
                    "attempt=" + attempt
                            + "/" + maxAttempts
                            + ", tool=" + toolCall.toolName()
                            + ", retryable=" + retryable
                            + ", " + detail
                            + ", durationMs=" + toolDurationMs,
                    toolDurationMs);
            if (result.success() || !retryable || attempt >= maxAttempts) {
                return IdempotentToolOutcome.completed(result);
            }
            addStep(trace, steps, "tool.retry", "READY",
                    "retrying tool after retryable failure, tool=" + toolCall.toolName()
                            + ", nextAttempt=" + (attempt + 1)
                            + ", error=" + result.errorMessage());
            sleepToolRetryBackoff(attempt);
        }
        ToolCallResult result = lastResult == null
                ? new ToolCallResult(toolCall.toolName(), false, "", "tool execution produced no result", Map.of())
                : lastResult;
        return IdempotentToolOutcome.completed(result);
    }

    private IdempotentToolOutcome executeToolOnce(String runId, ToolCallRequest toolCall) {
        // 先用 toolCallId 抢占执行权。相同 ID 已成功时直接复用结果，
        // 状态不确定或仍在执行时进入人工核对，避免重复产生业务副作用。
        ToolExecutionClaim claim = toolExecutionStore.claim(runId, toolCall);
        if (!claim.claimed()) {
            if (claim.state() == ToolExecutionState.SUCCEEDED && claim.cachedResult() != null) {
                Map<String, Object> metadata = new LinkedHashMap<>(claim.cachedResult().metadata());
                metadata.put("idempotentReplay", true);
                metadata.put("toolCallId", toolCall.requestId());
                return IdempotentToolOutcome.completed(new ToolCallResult(
                        claim.cachedResult().toolName(),
                        true,
                        claim.cachedResult().content(),
                        claim.cachedResult().errorMessage(),
                        metadata
                ));
            }
            return IdempotentToolOutcome.manualReview(
                    "toolCallId=" + toolCall.requestId() + " has state=" + claim.state()
                            + "; verify the business side effect before any retry"
            );
        }

        ToolCallResult result;
        try {
            // 只有成功 claim 的调用才会真正进入 ToolExecutor。
            result = toolExecutor.execute(toolCall);
        }
        catch (RuntimeException exception) {
            markToolManualReviewBestEffort(toolCall.requestId(),
                    "tool threw before a reliable result was persisted: " + exception.getClass().getSimpleName());
            return IdempotentToolOutcome.manualReview(
                    "工具执行结果未知，请人工核对，toolCallId=" + toolCall.requestId()
            );
        }

        if (result == null) {
            result = new ToolCallResult(
                    toolCall.toolName(),
                    false,
                    "",
                    "tool execution produced no result",
                    Map.of("toolCallId", toolCall.requestId())
            );
        }

        try {
            // 工具结果必须可靠落库后才能认为这次副作用闭环完成。
            if (result != null && result.success()) {
                toolExecutionStore.markSucceeded(toolCall.requestId(), result);
            }
            else {
                toolExecutionStore.markFailed(toolCall.requestId(), result);
            }
            return IdempotentToolOutcome.completed(result);
        }
        catch (RuntimeException persistenceFailure) {
            markToolManualReviewBestEffort(toolCall.requestId(),
                    "tool returned but result persistence failed: " + persistenceFailure.getClass().getSimpleName());
            return IdempotentToolOutcome.manualReview(
                    "工具可能已执行但结果落库失败，请人工核对，toolCallId=" + toolCall.requestId()
            );
        }
    }

    private void markToolManualReviewBestEffort(String toolCallId, String reason) {
        try {
            toolExecutionStore.markManualReview(toolCallId, reason);
        }
        catch (RuntimeException ignored) {
            // The Agent Run will still enter MANUAL_REVIEW and preserve the toolCallId.
        }
    }

    private boolean retryableToolFailure(ToolCallResult result) {
        if (result == null || result.success()) {
            return false;
        }
        String error = result.errorMessage() == null ? "" : result.errorMessage().toLowerCase(Locale.ROOT);
        Object provider = result.metadata().get("provider");
        if ("mcp".equals(String.valueOf(provider))) {
            return true;
        }
        if (result.metadata().containsKey("validation")) {
            return false;
        }
        if (error.contains("unknown tool") || error.contains("not found") || error.contains("不存在")) {
            return false;
        }
        return error.contains("timeout")
                || error.contains("temporary")
                || error.contains("network")
                || error.contains("failed")
                || error.contains("exception")
                || error.contains("unavailable");
    }

    private void sleepToolRetryBackoff(int attempt) {
        long base = Math.max(0, agentProperties.getToolRetryBackoffMillis());
        if (base <= 0) {
            return;
        }
        try {
            Thread.sleep(base * attempt);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private AgentResponse finishBlocked(AgentRequest request,
                                        String conversationId,
                                        TraceContext trace,
                                        List<AgentStep> steps,
                                        List<String> usedTools,
                                        boolean usedRag,
                                        boolean blockedByGuardrail,
                                        String answer) {
        return finish(request, conversationId, AgentRunStatus.BLOCKED, answer, steps, trace, usedTools, usedRag, blockedByGuardrail);
    }

    private AgentResponse finish(AgentRequest request,
                                 String conversationId,
                                 AgentRunStatus status,
                                 String answer,
                                 List<AgentStep> steps,
                                 TraceContext trace,
                                 List<String> usedTools,
                                 boolean usedRag,
                                 boolean blockedByGuardrail) {
        // 所有正常完成、澄清、阻断、失败和人工兜底分支最终都在这里统一收口。
        // 收口顺序：AgentRun -> Trace -> Workflow -> Eval -> HTTP 响应。
        boolean failureStatus = status == AgentRunStatus.FAILED
                || status == AgentRunStatus.BLOCKED
                || status == AgentRunStatus.REJECTED
                || status == AgentRunStatus.MANUAL_REVIEW;
        String failureReason = failureStatus ? answer : "";
        // 1. 持久化最终 AgentRun 状态和执行结果。
        AgentRunRecord persistedRun = agentRunStore.update(trace.traceId(), current -> current.finished(
                toAgentRunState(status),
                terminalNode(status, current.currentNode()),
                answer,
                failureReason,
                current.toolResults(),
                usedTools,
                usedRag,
                blockedByGuardrail
        ));
        // 2. 结束 Trace；JdbcTraceRecorder 会在 finish() 中把完整 TraceRun 快照写入 PostgreSQL。
        traceRecorder.recordReplay(trace, "run.status", "Agent run status changed to " + status, Map.of(
                "runId", trace.traceId(),
                "status", status.name(),
                "approvalId", persistedRun.approvalId()
        ));
        traceRecorder.markStatus(trace, status.name(), failureReason);
        TraceSummary traceSummary = traceRecorder.finish(trace);
        // 3. 将 Workflow 同步更新为对应终态。
        workflowRecorder.finish(trace.traceId(), toWorkflowStatus(status), failureReason);

        // 4. 记录轻量 Eval 事件，供后续 AgentOps 和自动评测统计使用。
        evalEventRecorder.record(new AgentRunEvalEvent(
                traceSummary.traceId(),
                conversationId,
                status,
                usedTools,
                usedRag,
                blockedByGuardrail,
                Instant.now()
        ));
        addStepAfterFinish(steps, "eval.record", "COMPLETED", "eval event recorded for status=" + status);
        // 5. 把回答、执行状态、审批 ID、步骤和 Trace 摘要返回给 Controller。
        return new AgentResponse(
                trace.traceId(),
                conversationId,
                status,
                answer,
                persistedRun.approvalId(),
                steps,
                traceSummary
        );
    }

    private void persistExistingWorkflowCheckpoints(TraceContext trace, List<AgentStep> steps) {
        for (AgentStep step : steps) {
            // 根据名称映射成 WorkflowNode
            WorkflowNode node = workflowPlanner.mapStepName(step.name());
            workflowRecorder.checkpoint(trace.traceId(), new WorkflowCheckpoint(
                    node,
                    step.status(),
                    step.summary(),
                    workflowPlanner.retryable(node),
                    workflowPlanner.resumable(node),
                    Instant.now()
            ));
        }
    }

    private void addStep(TraceContext trace, List<AgentStep> steps, String name, String status, String summary) {
        addStep(trace, steps, name, status, summary, parseDurationMs(summary));
    }

    private void addStep(TraceContext trace, List<AgentStep> steps, String name, String status, String summary, long durationMs) {
        // 一次业务步骤同时进入三个视角：
        // 1) AgentStep 返回给调用方；2) TraceSpan 用于观测/回放；3) WorkflowCheckpoint 用于状态查询和恢复。
        steps.add(new AgentStep(name, status, summary));
        traceRecorder.recordSpan(
                trace,
                name,
                inferSpanKind(name),
                inferSpanStatus(status),
                summary,
                durationMs,
                "",
                summary,
                "FAILED".equalsIgnoreCase(status) ? summary : "",
                Map.of("stepStatus", status)
        );
        workflowRecorder.checkpoint(trace.traceId(), new WorkflowCheckpoint(
                workflowPlanner.mapStepName(name),
                status,
                summary,
                workflowPlanner.retryable(workflowPlanner.mapStepName(name)),
                workflowPlanner.resumable(workflowPlanner.mapStepName(name)),
                Instant.now()
        ));
    }

    private void addStepAfterFinish(List<AgentStep> steps, String name, String status, String summary) {
        steps.add(new AgentStep(name, status, summary));
    }

    /**
     * 规范化 conversationId，去除首尾空格
     */
    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId.trim();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 解析持续时间（毫秒）
     * 例如：String summary = "mode=hybrid, documents=3, durationMs=127, hits=[...]";
     * 会解析出 durationMs=127
     */
    private long parseDurationMs(String summary) {
        if (summary == null || summary.isBlank()) {
            return 0;
        }
        Matcher matcher = DURATION_PATTERN.matcher(summary);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private TraceSpanKind inferSpanKind(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (value.contains("memory")) return TraceSpanKind.MEMORY;
        if (value.contains("guardrail")) return TraceSpanKind.GUARDRAIL;
        if (value.contains("skill")) return TraceSpanKind.SKILL;
        if (value.contains("intent")) return TraceSpanKind.ROUTER;
        if (value.contains("rewrite")) return TraceSpanKind.QUERY_REWRITE;
        if (value.contains("rag")) return TraceSpanKind.RAG;
        if (value.contains("tool")) return TraceSpanKind.TOOL;
        if (value.contains("approval")) return TraceSpanKind.APPROVAL;
        if (value.contains("prompt")) return TraceSpanKind.PROMPT;
        if (value.contains("llm")) return TraceSpanKind.LLM;
        if (value.contains("eval")) return TraceSpanKind.EVAL;
        if (value.contains("error")) return TraceSpanKind.ERROR;
        return TraceSpanKind.SYSTEM;
    }

    private TraceSpanStatus inferSpanStatus(String status) {
        if (status == null || status.isBlank()) {
            return TraceSpanStatus.COMPLETED;
        }
        try {
            return TraceSpanStatus.valueOf(status.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ignored) {
            return switch (status.toUpperCase(Locale.ROOT)) {
                case "ALLOW", "REDACT", "READY" -> TraceSpanStatus.COMPLETED;
                case "REQUIRE_APPROVAL" -> TraceSpanStatus.STARTED;
                default -> TraceSpanStatus.COMPLETED;
            };
        }
    }

    private WorkflowRunStatus toWorkflowStatus(AgentRunStatus status) {
        return switch (status) {
            case RUNNING -> WorkflowRunStatus.RUNNING;
            case WAITING_APPROVAL -> WorkflowRunStatus.WAITING_APPROVAL;
            case COMPLETED -> WorkflowRunStatus.COMPLETED;
            case BLOCKED -> WorkflowRunStatus.BLOCKED;
            case FAILED -> WorkflowRunStatus.FAILED;
            case REJECTED -> WorkflowRunStatus.REJECTED;
            case MANUAL_REVIEW -> WorkflowRunStatus.MANUAL_REVIEW;
            case NEEDS_CLARIFICATION -> WorkflowRunStatus.RESUMABLE;
        };
    }

    private AgentRunState toAgentRunState(AgentRunStatus status) {
        return switch (status) {
            case RUNNING -> AgentRunState.RUNNING;
            case WAITING_APPROVAL -> AgentRunState.WAITING_APPROVAL;
            case COMPLETED -> AgentRunState.COMPLETED;
            case NEEDS_CLARIFICATION -> AgentRunState.NEEDS_CLARIFICATION;
            case BLOCKED -> AgentRunState.BLOCKED;
            case FAILED -> AgentRunState.FAILED;
            case REJECTED -> AgentRunState.REJECTED;
            case MANUAL_REVIEW -> AgentRunState.MANUAL_REVIEW;
        };
    }

    private WorkflowNode terminalNode(AgentRunStatus status, WorkflowNode currentNode) {
        return switch (status) {
            case WAITING_APPROVAL, REJECTED -> WorkflowNode.TOOL_APPROVAL;
            case MANUAL_REVIEW -> WorkflowNode.TOOL_EXECUTE;
            case FAILED -> WorkflowNode.FAILED;
            case BLOCKED -> WorkflowNode.BLOCKED;
            case RUNNING -> currentNode == null ? WorkflowNode.START : currentNode;
            default -> WorkflowNode.FINISH;
        };
    }

    private void recordUsage(TraceContext trace, PromptRequest prompt, String answer) {
        LlmUsage usage = llmService.lastUsage()
                .filter(LlmUsage::hasTokenUsage)
                .orElse(null);
        if (usage != null) {
            long promptTokens = usage.promptTokens() > 0 ? usage.promptTokens() : Math.max(0, usage.totalTokens() - usage.completionTokens());
            long completionTokens = usage.completionTokens();
            double estimatedCost = (promptTokens * 0.000001) + (completionTokens * 0.000002);
            traceRecorder.recordTokenUsage(trace, promptTokens, completionTokens, estimatedCost);
            traceRecorder.recordMetric(trace, "tokenUsageSource", usage.source());
            traceRecorder.recordMetric(trace, "model", usage.model());
            traceRecorder.recordMetric(trace, "totalTokens", usage.totalTokens());
            traceRecorder.recordMetric(trace, "cacheReadInputTokens", usage.cacheReadInputTokens());
            traceRecorder.recordMetric(trace, "cacheWriteInputTokens", usage.cacheWriteInputTokens());
            traceRecorder.recordMetric(trace, "contextBlocks", prompt.contextBlocks().size());
            return;
        }
        long promptTokens = estimateTokens(prompt.systemPrompt())
                + estimateTokens(prompt.userPrompt())
                + prompt.contextBlocks().stream().mapToLong(this::estimateTokens).sum();
        long completionTokens = estimateTokens(answer);
        double estimatedCost = (promptTokens * 0.000001) + (completionTokens * 0.000002);
        traceRecorder.recordTokenUsage(trace, promptTokens, completionTokens, estimatedCost);
        traceRecorder.recordMetric(trace, "tokenUsageSource", "estimated");
        traceRecorder.recordMetric(trace, "contextBlocks", prompt.contextBlocks().size());
    }

    private long estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(value.length() / 4.0));
    }

    private String ragHitSummary(RagResult ragResult) {
        if (ragResult.documents().isEmpty()) {
            return "[]";
        }
        return ragResult.documents().stream()
                .map(document -> {
                    Object source = document.metadata().getOrDefault("source", document.title());
                    Object chunkIndex = document.metadata().getOrDefault("chunkIndex", "unknown");
                    Object rank = document.metadata().getOrDefault("rank", "unknown");
                    return "#" + rank + " " + source + "[" + chunkIndex + "] score=" + String.format(Locale.ROOT, "%.4f", document.score());
                })
                .toList()
                .toString();
    }

    private record ToolExecutionOutcome(
            List<String> usedTools,
            List<ToolCallResult> toolResults,
            String blockedAnswer,
            boolean blockedByGuardrail,
            AgentRunStatus terminalStatus,
            String terminalAnswer,
            String approvalId
    ) {

        static ToolExecutionOutcome completed(List<String> usedTools, List<ToolCallResult> results) {
            return new ToolExecutionOutcome(usedTools, results, null, false, null, null, "");
        }

        static ToolExecutionOutcome blocked(String answer, boolean blockedByGuardrail) {
            return new ToolExecutionOutcome(List.of(), List.of(), answer, blockedByGuardrail, null, null, "");
        }

        static ToolExecutionOutcome waitingApproval(List<String> usedTools,
                                                    List<ToolCallResult> results,
                                                    String approvalId,
                                                    String answer) {
            return new ToolExecutionOutcome(
                    usedTools,
                    results,
                    null,
                    false,
                    AgentRunStatus.WAITING_APPROVAL,
                    answer,
                    approvalId
            );
        }

        static ToolExecutionOutcome manualReview(List<String> usedTools,
                                                 List<ToolCallResult> results,
                                                 String reason) {
            return new ToolExecutionOutcome(
                    usedTools,
                    results,
                    null,
                    false,
                    AgentRunStatus.MANUAL_REVIEW,
                    reason,
                    ""
            );
        }
    }

    private record IdempotentToolOutcome(
            ToolCallResult result,
            boolean manualReview,
            String reason
    ) {

        static IdempotentToolOutcome completed(ToolCallResult result) {
            return new IdempotentToolOutcome(result, false, "");
        }

        static IdempotentToolOutcome manualReview(String reason) {
            return new IdempotentToolOutcome(null, true, reason);
        }
    }
}
