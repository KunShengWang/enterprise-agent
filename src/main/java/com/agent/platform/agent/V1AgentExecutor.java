package com.agent.platform.agent;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.eval.AgentRunEvalEvent;
import com.agent.platform.eval.EvalEventRecorder;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.llm.LlmCallException;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.prompt.PromptAssembler;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.query.QueryRewriteService;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
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
import com.agent.platform.trace.TraceSummary;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Primary
@Service
public class V1AgentExecutor implements AgentExecutor {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

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
                           EvalEventRecorder evalEventRecorder) {
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
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        TraceContext trace = traceRecorder.start(conversationId, request.question());
        List<AgentStep> steps = new ArrayList<>();
        List<String> usedTools = new ArrayList<>();
        boolean usedRag = false;
        boolean blockedByGuardrail = false;

        try {
            ConversationMemory memory = memoryService.load(conversationId, request.userId(), request.question());
            addStep(trace, steps, "memory.load", "COMPLETED",
                    "messages=" + memory.messages().size()
                            + ", longTerm=" + memory.longTermMemories().size()
                            + ", profileItems=" + memory.userProfile().items().size()
                            + ", recalled=" + memory.recalledMemories().size());
            memoryService.append(conversationId, request.userId(), new MemoryMessage("user", request.question(), Instant.now()));

            GuardrailDecision inputDecision = guardrailService.checkInput(request.question());
            addStep(trace, steps, "guardrail.input", inputDecision.action().name(), inputDecision.reason());
            if (inputDecision.action() == GuardrailAction.BLOCK) {
                blockedByGuardrail = true;
                return finishBlocked(request, conversationId, trace, steps, usedTools, usedRag, true,
                        "请求被输入护栏拦截：" + inputDecision.reason());
            }

            Optional<SkillDefinition> skill = skillSelector.select(request, memory);
            addStep(trace, steps, "skill.select", skill.map(SkillDefinition::name).orElse("NONE"), skill.map(SkillDefinition::description).orElse("no skill selected"));

            IntentRoute route = intentRouter.route(request, memory);
            addStep(trace, steps, "intent.route", route.type().name(), route.reason());
            if (route.type() == IntentType.CLARIFY) {
                String answer = "你的问题还不够明确。请补充工单编号、故障现象，或者说明你想查询哪类知识库资料。";
                memoryService.append(conversationId, request.userId(), new MemoryMessage("assistant", answer, Instant.now()));
                return finish(request, conversationId, AgentRunStatus.NEEDS_CLARIFICATION, answer, steps, trace, usedTools, usedRag, false);
            }

            String rewrittenQuery = queryRewriteService.rewrite(request, memory);
            addStep(trace, steps, "query.rewrite", "COMPLETED", rewrittenQuery);

            RagResult ragResult = RagResult.empty(rewrittenQuery);
            List<ToolCallResult> toolResults = new ArrayList<>();

            if (route.type() == IntentType.RAG) {
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
                ToolExecutionOutcome outcome = executeToolBranch(request, memory, conversationId, route, trace, steps);
                usedTools.addAll(outcome.usedTools());
                toolResults.addAll(outcome.toolResults());
                if (outcome.blockedAnswer() != null) {
                    blockedByGuardrail = outcome.blockedByGuardrail();
                    return finishBlocked(request, conversationId, trace, steps, usedTools, false, blockedByGuardrail, outcome.blockedAnswer());
                }
            }
            else {
                addStep(trace, steps, "chat.fallback", "COMPLETED", "general chat uses prompt without RAG or tool");
            }

            PromptRequest prompt = promptAssembler.assemble(request, memory, ragResult, toolResults);
            addStep(trace, steps, "prompt.assemble", "COMPLETED", "contextBlocks=" + prompt.contextBlocks().size());

            String answer;
            long llmStartNanos = System.nanoTime();
            try {
                answer = llmService.complete(prompt);
                addStep(trace, steps, "llm.call", "COMPLETED",
                        "real llm generated answer, durationMs=" + elapsedMs(llmStartNanos));
            }
            catch (LlmCallException exception) {
                addStep(trace, steps, "llm.call", "FAILED",
                        "errorType=" + exception.errorType() + ", durationMs=" + elapsedMs(llmStartNanos));
                return finish(request, conversationId, AgentRunStatus.FAILED, exception.safeMessage(), steps, trace, usedTools, usedRag, blockedByGuardrail);
            }

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

            memoryService.append(conversationId, request.userId(), new MemoryMessage("assistant", answer, Instant.now()));
            addStep(trace, steps, "conversation.save", "COMPLETED", "conversation messages appended");
            return finish(request, conversationId, AgentRunStatus.COMPLETED, answer, steps, trace, usedTools, usedRag, blockedByGuardrail);
        }
        catch (RuntimeException exception) {
            addStep(trace, steps, "agent.error", "FAILED", "errorType=" + exception.getClass().getSimpleName());
            return finish(request, conversationId, AgentRunStatus.FAILED, "Agent 执行失败，请稍后重试或根据 traceId 排查。", steps, trace, usedTools, usedRag, blockedByGuardrail);
        }
    }

    private ToolExecutionOutcome executeToolBranch(AgentRequest request,
                                                   ConversationMemory memory,
                                                   String conversationId,
                                                   IntentRoute route,
                                                   TraceContext trace,
                                                   List<AgentStep> steps) {
        List<ToolDefinition> availableTools = toolRegistry.listTools();
        addStep(trace, steps, "tool.registry", "COMPLETED", "availableTools=" + availableTools.stream().map(ToolDefinition::name).toList());
        if (availableTools.isEmpty()) {
            return ToolExecutionOutcome.blocked("当前没有可用工具，无法执行该任务。", true);
        }

        List<String> usedTools = new ArrayList<>();
        List<ToolCallResult> toolResults = new ArrayList<>();
        Set<String> executedSignatures = new HashSet<>();
        int maxCalls = Math.max(1, agentProperties.getMaxToolCallsPerRun());

        for (int index = 0; index < maxCalls; index++) {
            ToolCallPlan plan = toolCallPlanner.plan(request, memory, route, availableTools, toolResults);
            if (!plan.shouldCallTool()) {
                addStep(trace, steps, "tool.plan", "STOPPED", plan.reason());
                if (toolResults.isEmpty()) {
                    return ToolExecutionOutcome.blocked("未能生成可执行工具调用计划：" + plan.reason(), true);
                }
                break;
            }

            Optional<ToolDefinition> definition = toolRegistry.findTool(plan.toolName());
            if (definition.isEmpty()) {
                addStep(trace, steps, "tool.registry", "FAILED", "tool not found: " + plan.toolName());
                return ToolExecutionOutcome.blocked("没有找到可执行工具：" + plan.toolName(), true);
            }

            ToolCallRequest toolCall = new ToolCallRequest(plan.toolName(), UUID.randomUUID().toString(), plan.arguments());
            String signature = toolCall.toolName() + toolCall.arguments();
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

            GuardrailDecision toolDecision = guardrailService.checkToolCall(definition.get(), toolCall);
            addStep(trace, steps, "guardrail.tool", toolDecision.action().name(), toolDecision.reason());
            if (toolDecision.action() == GuardrailAction.BLOCK) {
                return ToolExecutionOutcome.blocked("工具调用被护栏拦截：" + toolDecision.reason(), true);
            }
            if (toolDecision.action() == GuardrailAction.REQUIRE_APPROVAL) {
                ApprovalRequest approvalRequest = new ApprovalRequest(
                        UUID.randomUUID().toString(),
                        conversationId,
                        toolCall,
                        toolDecision.reason(),
                        Instant.now()
                );
                ApprovalDecision approvalDecision = approvalService.requestApproval(approvalRequest);
                addStep(trace, steps, "approval.request", approvalDecision.approved() ? "APPROVED" : "REJECTED", approvalDecision.reason());
                if (!approvalDecision.approved()) {
                    return ToolExecutionOutcome.blocked("高风险工具未通过人工确认，已停止执行。", false);
                }
            }

            long toolStartNanos = System.nanoTime();
            ToolCallResult result = toolExecutor.execute(toolCall);
            String detail = result.success() ? result.content() : result.errorMessage();
            addStep(trace, steps, "tool.execute", result.success() ? "COMPLETED" : "FAILED",
                    detail + ", durationMs=" + elapsedMs(toolStartNanos));
            usedTools.add(toolCall.toolName());
            toolResults.add(result);
            if (!result.success()) {
                break;
            }
        }
        return ToolExecutionOutcome.completed(usedTools, toolResults);
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
        TraceSummary traceSummary = traceRecorder.finish(trace);
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
        return new AgentResponse(conversationId, status, answer, steps, traceSummary);
    }

    private void addStep(TraceContext trace, List<AgentStep> steps, String name, String status, String summary) {
        steps.add(new AgentStep(name, status, summary));
        traceRecorder.record(trace, name, summary);
    }

    private void addStepAfterFinish(List<AgentStep> steps, String name, String status, String summary) {
        steps.add(new AgentStep(name, status, summary));
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId.trim();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
            boolean blockedByGuardrail
    ) {

        static ToolExecutionOutcome completed(List<String> usedTools, List<ToolCallResult> results) {
            return new ToolExecutionOutcome(usedTools, results, null, false);
        }

        static ToolExecutionOutcome blocked(String answer, boolean blockedByGuardrail) {
            return new ToolExecutionOutcome(List.of(), List.of(), answer, blockedByGuardrail);
        }
    }
}
