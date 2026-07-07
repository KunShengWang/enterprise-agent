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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DefaultMultiAgentOrchestrator implements MultiAgentOrchestrator {

    private final MemoryService memoryService;
    private final IntentRouter intentRouter;
    private final RagService ragService;
    private final ToolRegistry toolRegistry;
    private final ToolCallPlanner toolCallPlanner;
    private final ToolExecutor toolExecutor;
    private final LlmService llmService;

    public DefaultMultiAgentOrchestrator(MemoryService memoryService,
                                         IntentRouter intentRouter,
                                         RagService ragService,
                                         ToolRegistry toolRegistry,
                                         ToolCallPlanner toolCallPlanner,
                                         ToolExecutor toolExecutor,
                                         LlmService llmService) {
        this.memoryService = memoryService;
        this.intentRouter = intentRouter;
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
        this.toolCallPlanner = toolCallPlanner;
        this.toolExecutor = toolExecutor;
        this.llmService = llmService;
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

        RagResult ragResult = RagResult.empty(request.question());
        List<ToolCallResult> toolResults = new ArrayList<>();
        if (tasks.stream().anyMatch(task -> task.role() == MultiAgentRole.RAG_WORKER)) {
            ragResult = ragService.retrieve(request.question(), 3);
            messages.add(message(MultiAgentRole.RAG_WORKER, "rag", "retrieved documents=" + ragResult.documents().size(), Map.of("enoughEvidence", ragResult.enoughEvidence())));
        }
        if (tasks.stream().anyMatch(task -> task.role() == MultiAgentRole.TOOL_WORKER)) {
            List<ToolDefinition> tools = toolRegistry.listTools();
            ToolCallPlan plan = toolCallPlanner.plan(request, memory, route, tools, toolResults);
            if (plan.shouldCallTool()) {
                ToolCallResult result = toolExecutor.execute(new ToolCallRequest(plan.toolName(), UUID.randomUUID().toString(), plan.arguments()));
                toolResults.add(result);
                messages.add(message(MultiAgentRole.TOOL_WORKER, "tool", "tool=" + plan.toolName() + ", success=" + result.success(), Map.of("result", result.content())));
            }
            else {
                messages.add(message(MultiAgentRole.TOOL_WORKER, "tool", "no tool call: " + plan.reason(), Map.of()));
            }
        }
        String finalAnswer = reviewerAnswer(request, messages, ragResult, toolResults);
        messages.add(message(MultiAgentRole.REVIEWER, "reviewer", finalAnswer, Map.of()));
        Instant finishedAt = Instant.now();
        return new MultiAgentRunResponse(
                runId,
                conversationId,
                request.question(),
                finalAnswer,
                tasks,
                messages,
                startedAt,
                finishedAt,
                Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
                Map.of("messageCount", messages.size(), "taskCount", tasks.size(), "route", route.type().name())
        );
    }

    private List<MultiAgentTask> planTasks(IntentRoute route, AgentRequest request) {
        List<MultiAgentTask> tasks = new ArrayList<>();
        tasks.add(new MultiAgentTask("planner", MultiAgentRole.PLANNER, "拆解用户问题并决定子 Agent 分工", Map.of("question", request.question())));
        if (route.type() == IntentType.RAG) {
            tasks.add(new MultiAgentTask("rag", MultiAgentRole.RAG_WORKER, "检索知识库证据并返回来源", Map.of()));
        }
        else if (route.type() == IntentType.TOOL) {
            tasks.add(new MultiAgentTask("tool", MultiAgentRole.TOOL_WORKER, "选择并执行合适工具", Map.of()));
        }
        else if (route.type() == IntentType.CHAT) {
            tasks.add(new MultiAgentTask("rag", MultiAgentRole.RAG_WORKER, "必要时补充知识库背景", Map.of("optional", true)));
        }
        tasks.add(new MultiAgentTask("reviewer", MultiAgentRole.REVIEWER, "检查子结果并聚合最终回答", Map.of()));
        return tasks;
    }

    private String reviewerAnswer(AgentRequest request,
                                  List<MultiAgentMessage> messages,
                                  RagResult ragResult,
                                  List<ToolCallResult> toolResults) {
        List<String> contextBlocks = new ArrayList<>();
        messages.forEach(message -> contextBlocks.add(message.role() + ": " + message.content()));
        ragResult.documents().forEach(document -> contextBlocks.add("RAG: " + document.title() + " score=" + document.score() + " " + document.content()));
        toolResults.forEach(result -> contextBlocks.add("Tool: " + result.toolName() + " success=" + result.success() + " " + result.content()));
        return llmService.complete(new PromptRequest(
                "你是 Multi-Agent Reviewer。请综合 Planner、Worker 和工具/检索结果，给出简洁中文最终回答；资料不足时明确说明。",
                "用户问题：" + request.question(),
                contextBlocks,
                Map.of("mode", "multi-agent")
        ));
    }

    private MultiAgentMessage message(MultiAgentRole role, String taskId, String content, Map<String, Object> metadata) {
        return new MultiAgentMessage(role, taskId, content, Instant.now(), metadata);
    }
}
