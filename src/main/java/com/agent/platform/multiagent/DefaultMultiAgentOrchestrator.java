package com.agent.platform.multiagent;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentRouter;
import com.agent.platform.router.IntentType;
import com.agent.platform.tool.ToolCallPlan;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolCallPlanner;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class DefaultMultiAgentOrchestrator implements MultiAgentOrchestrator {

    private final MemoryService memoryService;
    private final IntentRouter intentRouter;
    private final RagService ragService;
    private final ToolRegistry toolRegistry;
    private final ToolCallPlanner toolCallPlanner;
    private final ToolExecutor toolExecutor;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public DefaultMultiAgentOrchestrator(MemoryService memoryService,
                                         IntentRouter intentRouter,
                                         RagService ragService,
                                         ToolRegistry toolRegistry,
                                         ToolCallPlanner toolCallPlanner,
                                         ToolExecutor toolExecutor,
                                         LlmService llmService,
                                         ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.intentRouter = intentRouter;
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
        this.toolCallPlanner = toolCallPlanner;
        this.toolExecutor = toolExecutor;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public MultiAgentRunResponse execute(AgentRequest request) {
        Instant startedAt = Instant.now();
        String runId = UUID.randomUUID().toString();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? "multi-agent-" + runId
                : request.conversationId();
        ConversationMemory memory = memoryService.load(conversationId, request.userId(), request.question());
        IntentRoute route = intentRouter.route(request, memory);
        List<MultiAgentTask> tasks = planTasks(route, request);
        List<MultiAgentMessage> messages = new ArrayList<>();
        messages.add(message(MultiAgentRole.PLANNER, "planner", "route=" + route.type() + ", reason=" + route.reason(), Map.of("slots", route.slots())));

        boolean shouldRunRag = tasks.stream().anyMatch(task -> task.role() == MultiAgentRole.RAG_WORKER);
        boolean shouldRunTool = tasks.stream().anyMatch(task -> task.role() == MultiAgentRole.TOOL_WORKER);
        CompletableFuture<RagWorkerResult> ragFuture = shouldRunRag
                ? CompletableFuture.supplyAsync(() -> runRagWorker(request))
                : CompletableFuture.completedFuture(new RagWorkerResult(RagResult.empty(request.question()), message(MultiAgentRole.RAG_WORKER, "rag", "rag worker skipped", Map.of("skipped", true))));
        CompletableFuture<ToolWorkerResult> toolFuture = shouldRunTool
                ? CompletableFuture.supplyAsync(() -> runToolWorker(request, memory, route))
                : CompletableFuture.completedFuture(new ToolWorkerResult(List.of(), message(MultiAgentRole.TOOL_WORKER, "tool", "tool worker skipped", Map.of("skipped", true))));
        CompletableFuture.allOf(ragFuture, toolFuture).join();
        RagWorkerResult ragWorkerResult = ragFuture.join();
        ToolWorkerResult toolWorkerResult = toolFuture.join();
        RagResult ragResult = ragWorkerResult.ragResult();
        List<ToolCallResult> toolResults = toolWorkerResult.toolResults();
        if (shouldRunRag) {
            messages.add(ragWorkerResult.message());
        }
        if (shouldRunTool) {
            messages.add(toolWorkerResult.message());
        }
        MultiAgentReviewResult review = reviewerAnswer(request, messages, ragResult, toolResults);
        messages.add(message(MultiAgentRole.REVIEWER, "reviewer", review.finalAnswer(), Map.of(
                "approved", review.approved(),
                "confidence", review.confidence(),
                "conflictDetected", review.conflictDetected(),
                "conflictReason", review.conflictReason(),
                "evidence", review.evidence()
        )));
        Instant finishedAt = Instant.now();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("messageCount", messages.size());
        metrics.put("taskCount", tasks.size());
        metrics.put("route", route.type().name());
        metrics.put("workerMode", "parallel");
        metrics.put("reviewApproved", review.approved());
        metrics.put("reviewConfidence", review.confidence());
        metrics.put("conflictDetected", review.conflictDetected());
        return new MultiAgentRunResponse(
                runId,
                conversationId,
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

    private List<MultiAgentTask> planTasks(IntentRoute route, AgentRequest request) {
        List<MultiAgentTask> tasks = new ArrayList<>();
        tasks.add(new MultiAgentTask("planner", MultiAgentRole.PLANNER, "拆解用户问题并决定子 Agent 分工", Map.of("question", request.question())));
        if (route.type() == IntentType.RAG) {
            tasks.add(new MultiAgentTask("rag", MultiAgentRole.RAG_WORKER, "检索知识库证据并返回来源", Map.of()));
        }
        else if (route.type() == IntentType.TOOL) {
            tasks.add(new MultiAgentTask("rag", MultiAgentRole.RAG_WORKER, "并行检索相关知识库背景，辅助 Reviewer 判断工具结果", Map.of("optional", true)));
            tasks.add(new MultiAgentTask("tool", MultiAgentRole.TOOL_WORKER, "选择并执行合适工具", Map.of()));
        }
        else if (route.type() == IntentType.CHAT) {
            tasks.add(new MultiAgentTask("rag", MultiAgentRole.RAG_WORKER, "必要时补充知识库背景", Map.of("optional", true)));
        }
        tasks.add(new MultiAgentTask("reviewer", MultiAgentRole.REVIEWER, "检查子结果并聚合最终回答", Map.of()));
        return tasks;
    }

    private RagWorkerResult runRagWorker(AgentRequest request) {
        long startNanos = System.nanoTime();
        try {
            RagResult ragResult = ragService.retrieve(request.question(), 3);
            return new RagWorkerResult(
                    ragResult,
                    message(MultiAgentRole.RAG_WORKER, "rag", "retrieved documents=" + ragResult.documents().size(), Map.of(
                            "enoughEvidence", ragResult.enoughEvidence(),
                            "durationMs", elapsedMs(startNanos),
                            "parallel", true
                    ))
            );
        }
        catch (RuntimeException exception) {
            return new RagWorkerResult(
                    RagResult.empty(request.question()),
                    message(MultiAgentRole.RAG_WORKER, "rag", "rag worker failed: " + exception.getMessage(), Map.of(
                            "error", exception.getClass().getSimpleName(),
                            "durationMs", elapsedMs(startNanos),
                            "parallel", true
                    ))
            );
        }
    }

    private ToolWorkerResult runToolWorker(AgentRequest request, ConversationMemory memory, IntentRoute route) {
        long startNanos = System.nanoTime();
        try {
            List<ToolDefinition> tools = toolRegistry.listTools();
            ToolCallPlan plan = toolCallPlanner.plan(request, memory, route, tools, List.of());
            if (plan.shouldCallTool()) {
                ToolCallResult result = toolExecutor.execute(new ToolCallRequest(plan.toolName(), UUID.randomUUID().toString(), plan.arguments()));
                return new ToolWorkerResult(
                        List.of(result),
                        message(MultiAgentRole.TOOL_WORKER, "tool", "tool=" + plan.toolName() + ", success=" + result.success(), Map.of(
                                "result", result.content(),
                                "durationMs", elapsedMs(startNanos),
                                "parallel", true
                        ))
                );
            }
            return new ToolWorkerResult(
                    List.of(),
                    message(MultiAgentRole.TOOL_WORKER, "tool", "no tool call: " + plan.reason(), Map.of(
                            "durationMs", elapsedMs(startNanos),
                            "parallel", true
                    ))
            );
        }
        catch (RuntimeException exception) {
            return new ToolWorkerResult(
                    List.of(),
                    message(MultiAgentRole.TOOL_WORKER, "tool", "tool worker failed: " + exception.getMessage(), Map.of(
                            "error", exception.getClass().getSimpleName(),
                            "durationMs", elapsedMs(startNanos),
                            "parallel", true
                    ))
            );
        }
    }

    private MultiAgentReviewResult reviewerAnswer(AgentRequest request,
                                                  List<MultiAgentMessage> messages,
                                                  RagResult ragResult,
                                                  List<ToolCallResult> toolResults) {
        List<String> contextBlocks = new ArrayList<>();
        messages.forEach(message -> contextBlocks.add(message.role() + ": " + message.content()));
        ragResult.documents().forEach(document -> contextBlocks.add("RAG: " + document.title() + " score=" + document.score() + " " + document.content()));
        toolResults.forEach(result -> contextBlocks.add("Tool: " + result.toolName() + " success=" + result.success() + " " + result.content()));
        contextBlocks.add("Reviewer must output JSON only: {\"approved\":true,\"confidence\":0.0,\"conflictDetected\":false,\"conflictReason\":\"\",\"evidence\":[\"evidence\"],\"finalAnswer\":\"final Chinese answer\"}");
        String reviewText = llmService.complete(new PromptRequest(
                "你是 Multi-Agent Reviewer。请综合 Planner、Worker 和工具/检索结果，给出简洁中文最终回答；资料不足时明确说明。",
                "用户问题：" + request.question(),
                contextBlocks,
                Map.of("mode", "multi-agent")
        ));
        return parseReview(reviewText, ragResult, toolResults);
    }

    private MultiAgentReviewResult parseReview(String reviewText, RagResult ragResult, List<ToolCallResult> toolResults) {
        boolean deterministicConflict = deterministicConflict(ragResult, toolResults);
        try {
            Map<?, ?> raw = objectMapper.readValue(extractJsonObject(reviewText), Map.class);
            return new MultiAgentReviewResult(
                    booleanValue(raw.get("approved"), true),
                    doubleValue(raw.get("confidence"), 0.6),
                    booleanValue(raw.get("conflictDetected"), deterministicConflict),
                    stringValue(raw.get("conflictReason"), ""),
                    stringList(raw.get("evidence")),
                    stringValue(raw.get("finalAnswer"), reviewText)
            );
        }
        catch (RuntimeException ignored) {
            return new MultiAgentReviewResult(
                    true,
                    0.5,
                    deterministicConflict,
                    deterministicConflict ? "worker result contains failed tool result and retrieved evidence" : "",
                    fallbackEvidence(ragResult, toolResults),
                    reviewText
            );
        }
    }

    private boolean deterministicConflict(RagResult ragResult, List<ToolCallResult> toolResults) {
        boolean hasRagEvidence = ragResult != null && !ragResult.documents().isEmpty();
        boolean hasFailedTool = toolResults != null && toolResults.stream().anyMatch(result -> !result.success());
        return hasRagEvidence && hasFailedTool;
    }

    private List<String> fallbackEvidence(RagResult ragResult, List<ToolCallResult> toolResults) {
        List<String> evidence = new ArrayList<>();
        if (ragResult != null) {
            ragResult.documents().stream()
                    .limit(3)
                    .forEach(document -> evidence.add("RAG:" + document.title() + ", score=" + document.score()));
        }
        if (toolResults != null) {
            toolResults.forEach(result -> evidence.add("Tool:" + result.toolName() + ", success=" + result.success()));
        }
        return evidence;
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("model output is empty");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output does not contain JSON object");
        }
        return text.substring(start, end + 1);
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .toList();
    }

    private MultiAgentMessage message(MultiAgentRole role, String taskId, String content, Map<String, Object> metadata) {
        return new MultiAgentMessage(role, taskId, content, Instant.now(), metadata);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record RagWorkerResult(RagResult ragResult, MultiAgentMessage message) {
    }

    private record ToolWorkerResult(List<ToolCallResult> toolResults, MultiAgentMessage message) {
    }
}
