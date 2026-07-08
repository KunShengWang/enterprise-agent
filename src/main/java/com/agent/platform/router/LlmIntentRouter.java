package com.agent.platform.router;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.prompt.PromptRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Primary
@Service
public class LlmIntentRouter implements IntentRouter {

    private final LlmService llmService;

    private final ObjectMapper objectMapper;

    private final RuleBasedIntentRouter fallbackRouter;

    public LlmIntentRouter(LlmService llmService,
                           ObjectMapper objectMapper,
                           RuleBasedIntentRouter fallbackRouter) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.fallbackRouter = fallbackRouter;
    }

    @Override
    public IntentRoute route(AgentRequest request, ConversationMemory memory) {
        IntentRoute fallback = fallbackRouter.route(request, memory);
        try {
            IntentRoute llmRoute = parseRoute(callRouterModel(request, memory, fallback), fallback);
            if (llmRoute.type() != null) {
                return llmRoute;
            }
        }
        catch (RuntimeException ignored) {
            // Router must degrade deterministically. A bad model output should not fail the agent run.
        }
        return fallback;
    }

    private String callRouterModel(AgentRequest request, ConversationMemory memory, IntentRoute fallback) {
        List<String> contextBlocks = List.of(
                "recentMessages=" + formatRecentMessages(memory),
                "memorySummary=" + safe(memory == null ? "" : memory.summary()),
                "ruleFallbackType=" + fallback.type(),
                "ruleFallbackReason=" + fallback.reason(),
                "ruleFallbackSlots=" + fallback.slots()
        );
        return llmService.complete(new PromptRequest(
                """
                你是企业 Agent 的意图路由器。请判断用户问题应该进入哪条执行分支。
                只允许输出一个 JSON 对象，不要输出 Markdown，不要解释。
                type 只能是 CHAT、RAG、TOOL、CLARIFY。
                判断标准：
                - CHAT：普通问候、闲聊、概念解释，不需要知识库或工具。
                - RAG：需要查询企业知识库、制度、流程、故障处理资料。
                - TOOL：需要查询/创建/更新工单，或需要执行文件/MCP 等工具。
                - CLARIFY：问题过于模糊，缺少执行所需的关键参数。
                JSON 格式：
                {"type":"CHAT|RAG|TOOL|CLARIFY","reason":"选择原因","confidence":0.0,"slots":{"toolName":"可选","keywords":["可选"]}}
                """.strip(),
                "用户问题：" + (request == null ? "" : request.question()),
                contextBlocks,
                Map.of("purpose", "intent_routing")
        ));
    }

    private IntentRoute parseRoute(String modelOutput, IntentRoute fallback) {
        Map<?, ?> raw = objectMapper.readValue(extractJsonObject(modelOutput), Map.class);
        IntentType type = parseType(stringValue(raw.get("type"), fallback.type().name()), fallback.type());
        String reason = stringValue(raw.get("reason"), "llm routed request");
        double confidence = doubleValue(raw.get("confidence"), 0.5);
        Map<String, Object> slots = new LinkedHashMap<>(fallback.slots());
        slots.put("router", "llm");
        slots.put("routerConfidence", confidence);
        Object rawSlots = raw.get("slots");
        if (rawSlots instanceof Map<?, ?> slotMap) {
            for (Map.Entry<?, ?> entry : slotMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    slots.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return new IntentRoute(type, reason, slots);
    }

    private IntentType parseType(String value, IntentType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return IntentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String formatRecentMessages(ConversationMemory memory) {
        if (memory == null || memory.messages().isEmpty()) {
            return "[]";
        }
        return memory.messages().stream()
                .skip(Math.max(0, memory.messages().size() - 5))
                .map(this::formatMessage)
                .toList()
                .toString();
    }

    private String formatMessage(MemoryMessage message) {
        return message.role() + ":" + safe(message.content());
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

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
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

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
