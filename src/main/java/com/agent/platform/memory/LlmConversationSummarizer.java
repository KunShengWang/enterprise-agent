package com.agent.platform.memory;

import com.agent.platform.llm.LlmService;
import com.agent.platform.prompt.PromptRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由模型生成可演进的结构化会话摘要，规则摘要仅作为不可用时的降级。
 */
@Primary
@Service
public class LlmConversationSummarizer implements ConversationSummarizer {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final RuleBasedConversationSummarizer fallback;

    public LlmConversationSummarizer(LlmService llmService,
                                     ObjectMapper objectMapper,
                                     RuleBasedConversationSummarizer fallback) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public String summarize(String previousSummary, List<MemoryMessage> messages, int maxChars) {
        if (messages == null || messages.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        try {
            String raw = llmService.complete(new PromptRequest(
                    """
                    你是会话上下文压缩器。只输出 JSON，不要 Markdown。
                    仅保留未来轮次仍然有用且能由消息支持的信息，不推测、不编造。
                    必须保持以下结构：
                    {"summary":"简短事实摘要","decisions":["已确认决定"],"openTasks":["未完成任务"],"entities":["关键实体"],"preferences":["用户偏好"]}
                    工具调用和工具结果如果出现在输入中，必须作为同一事实理解，不能只保留其中一半。
                    """.strip(),
                    "请合并旧摘要与新增消息，目标最大字符数=" + Math.max(400, maxChars),
                    List.of(
                            "previousSummary=" + safe(previousSummary),
                            "newMessages=\n" + formatMessages(messages)
                    ),
                    Map.of("purpose", "memory_summary")
            ));
            return normalizeStructuredSummary(raw, Math.max(400, maxChars));
        }
        catch (RuntimeException exception) {
            return fallback.summarize(previousSummary, messages, maxChars);
        }
    }

    private String normalizeStructuredSummary(String raw, int maxChars) {
        Map<?, ?> parsed = objectMapper.readValue(extractJson(raw), Map.class);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("summary", stringValue(parsed.get("summary")));
        normalized.put("decisions", stringList(parsed.get("decisions"), 12));
        normalized.put("openTasks", stringList(parsed.get("openTasks"), 12));
        normalized.put("entities", stringList(parsed.get("entities"), 16));
        normalized.put("preferences", stringList(parsed.get("preferences"), 12));
        String json = objectMapper.writeValueAsString(normalized);
        while (json.length() > maxChars && removeLastListItem(normalized)) {
            json = objectMapper.writeValueAsString(normalized);
        }
        if (json.length() > maxChars) {
            String summary = stringValue(normalized.get("summary"));
            int reserved = Math.min(summary.length(), Math.max(80, maxChars / 2));
            normalized.put("summary", summary.substring(0, reserved));
            json = objectMapper.writeValueAsString(normalized);
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private boolean removeLastListItem(Map<String, Object> summary) {
        for (String key : List.of("entities", "preferences", "decisions", "openTasks")) {
            Object value = summary.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                List<String> mutable = new ArrayList<>((List<String>) list);
                mutable.remove(mutable.size() - 1);
                summary.put(key, List.copyOf(mutable));
                return true;
            }
        }
        return false;
    }

    private List<String> stringList(Object value, int maxItems) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> limit(String.valueOf(item).trim(), 240))
                .distinct()
                .limit(maxItems)
                .toList();
    }

    private String formatMessages(List<MemoryMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (MemoryMessage message : messages) {
            if (message != null && message.content() != null && !message.content().isBlank()) {
                builder.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        return builder.toString();
    }

    private String extractJson(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        int end = text == null ? -1 : text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("summary model output is not JSON");
        }
        return text.substring(start, end + 1);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String limit(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
