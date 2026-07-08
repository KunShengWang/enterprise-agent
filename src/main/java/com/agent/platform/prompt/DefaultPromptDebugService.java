package com.agent.platform.prompt;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.query.QueryRewriteService;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.router.IntentRoute;
import com.agent.platform.router.IntentRouter;
import com.agent.platform.router.IntentType;
import com.agent.platform.tool.ToolCallPlan;
import com.agent.platform.tool.ToolCallPlanner;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DefaultPromptDebugService implements PromptDebugService {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private final MemoryService memoryService;

    private final IntentRouter intentRouter;

    private final QueryRewriteService queryRewriteService;

    private final RagService ragService;

    private final ToolRegistry toolRegistry;

    private final ToolCallPlanner toolCallPlanner;

    private final PromptAssembler promptAssembler;

    public DefaultPromptDebugService(MemoryService memoryService,
                                     IntentRouter intentRouter,
                                     QueryRewriteService queryRewriteService,
                                     RagService ragService,
                                     ToolRegistry toolRegistry,
                                     ToolCallPlanner toolCallPlanner,
                                     PromptAssembler promptAssembler) {
        this.memoryService = memoryService;
        this.intentRouter = intentRouter;
        this.queryRewriteService = queryRewriteService;
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
        this.toolCallPlanner = toolCallPlanner;
        this.promptAssembler = promptAssembler;
    }

    @Override
    public PromptDebugResponse debug(AgentRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        ConversationMemory memory = memoryService.load(conversationId, request.userId(), request.question());
        IntentRoute route = intentRouter.route(request, memory);
        String rewrittenQuery = queryRewriteService.rewrite(request, memory);
        RagResult ragResult = RagResult.empty(rewrittenQuery);
        ToolCallPlan plannedTool = ToolCallPlan.noTool("dry-run prompt debug did not plan a tool", "debug");

        if (route.type() == IntentType.RAG) {
            ragResult = ragService.retrieve(rewrittenQuery, 3);
        }
        else if (route.type() == IntentType.TOOL) {
            List<ToolDefinition> tools = toolRegistry.listTools();
            plannedTool = toolCallPlanner.plan(request, memory, route, tools, List.of());
        }

        PromptRequest prompt = promptAssembler.assemble(request, memory, ragResult, List.of());
        return new PromptDebugResponse(
                conversationId,
                route.type().name(),
                route.reason(),
                rewrittenQuery,
                plannedTool.shouldCallTool() ? plannedTool.toolName() : "",
                plannedTool.shouldCallTool() ? plannedTool.arguments() : Map.of(),
                !ragResult.documents().isEmpty(),
                prompt,
                renderFullPrompt(prompt),
                Map.of(
                        "dryRun", true,
                        "toolExecuted", false,
                        "contextBlocks", prompt.contextBlocks().size(),
                        "routeSlots", route.slots()
                )
        );
    }

    private String renderFullPrompt(PromptRequest prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("SYSTEM:\n")
                .append(prompt.systemPrompt() == null ? "" : prompt.systemPrompt())
                .append("\n\nUSER:\n")
                .append(prompt.userPrompt() == null ? "" : prompt.userPrompt());
        if (!prompt.contextBlocks().isEmpty()) {
            builder.append("\n\nCONTEXT:\n");
            for (int index = 0; index < prompt.contextBlocks().size(); index++) {
                builder.append(index + 1)
                        .append(". ")
                        .append(prompt.contextBlocks().get(index))
                        .append('\n');
            }
        }
        return builder.toString();
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId.trim();
    }
}
