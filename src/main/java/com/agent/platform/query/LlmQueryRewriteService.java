package com.agent.platform.query;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.llm.LlmService;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.prompt.PromptRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Primary
@Service
public class LlmQueryRewriteService implements QueryRewriteService {

    private final LlmService llmService;

    private final ObjectMapper objectMapper;

    private final RuleBasedQueryRewriteService fallbackService;

    public LlmQueryRewriteService(LlmService llmService,
                                  ObjectMapper objectMapper,
                                  RuleBasedQueryRewriteService fallbackService) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.fallbackService = fallbackService;
    }

    /**
     * 用户问题改写
     */
    @Override
    public String rewrite(AgentRequest request, ConversationMemory memory) {
        String fallback = fallbackService.rewrite(request, memory);
        try {
            // 调用 query 改写 LLM 并解析返回的数据
            String rewritten = parseRewrite(callRewriteModel(request, memory, fallback));
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        }
        catch (RuntimeException ignored) {
            // Query rewrite is an optimization. Fall back to the deterministic query on bad model output.
        }
        return fallback;
    }

    /**
     * 调用 query 改写 LLM
     */
    private String callRewriteModel(AgentRequest request, ConversationMemory memory, String fallback) {
        return llmService.complete(new PromptRequest(
                """
                你是企业 Agent 的查询改写器。请把用户问题改写成更适合 RAG 检索和工具规划的查询。
                只输出一个 JSON 对象，不要输出 Markdown。
                要求：
                - 保留用户原意，不要添加事实。
                - 如果历史对话能补全省略指代，可以补全。
                - 如果原问题已经清楚，可以保持不变。
                JSON 格式：
                {"rewrittenQuery":"改写后的查询","reason":"改写原因","changed":true}
                """.strip(),
                "用户问题：" + (request == null ? "" : request.question()),
                List.of(
                        "recentMessages=" + formatRecentMessages(memory),
                        "memorySummary=" + (memory == null ? "" : memory.summary()),
                        "fallbackQuery=" + fallback
                ),
                Map.of("purpose", "query_rewrite")
        ));
    }

    private String parseRewrite(String modelOutput) {
        Map<?, ?> raw = objectMapper.readValue(extractJsonObject(modelOutput), Map.class);
        Object value = raw.get("rewrittenQuery");
        return value == null ? "" : String.valueOf(value);
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
        String content = message.content() == null ? "" : message.content();
        return message.role() + ":" + (content.length() <= 300 ? content : content.substring(0, 300));
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
}
