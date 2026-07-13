package com.agent.platform.memory;

import com.agent.platform.guardrail.SensitiveDataFilter;
import com.agent.platform.guardrail.SensitiveDataFilterResult;
import com.agent.platform.llm.LlmService;
import com.agent.platform.prompt.PromptRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通过 JSON Schema 风格协议提取长期记忆和用户画像，并进行脱敏、去重和置信度校验。
 */
@Primary
@Service
public class LlmMemoryExtractor implements MemoryExtractor {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "identity", "preference", "instruction", "business_fact", "decision", "open_task"
    );

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;

    public LlmMemoryExtractor(LlmService llmService,
                              ObjectMapper objectMapper,
                              SensitiveDataFilter sensitiveDataFilter) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    @Override
    public MemoryExtraction extract(String conversationId, String userId, MemoryMessage message) {
        if (message == null || !"user".equalsIgnoreCase(message.role())
                || message.content() == null || message.content().isBlank()) {
            return MemoryExtraction.empty();
        }
        try {
            String raw = llmService.complete(new PromptRequest(
                    """
                    你是长期记忆提取器，只输出 JSON，不要 Markdown。
                    仅提取用户明确表达、未来对话仍有价值的信息；不要保存临时闲聊、模型回答或未经确认的推测。
                    禁止提取密码、令牌、身份证、手机号、银行卡等敏感数据。
                    category 只能是 identity、preference、instruction、business_fact、decision、open_task。
                    JSON：{"longTermMemories":[{"category":"preference","content":"内容","confidence":0.0}],"profileItems":[{"key":"字段","value":"值","confidence":0.0}]}
                    没有可保存内容时返回两个空数组。
                    """.strip(),
                    "用户消息：" + message.content(),
                    List.of("conversationId=" + conversationId, "userId=" + userId),
                    Map.of("purpose", "memory_extraction")
            ));
            return parseExtraction(raw, conversationId, message.createdAt());
        }
        catch (RuntimeException exception) {
            // Extraction is a persistence decision. On model/protocol failure, do not guess and write false memory.
            return MemoryExtraction.empty();
        }
    }

    private MemoryExtraction parseExtraction(String raw, String conversationId, Instant createdAt) {
        Map<?, ?> root = objectMapper.readValue(extractJson(raw), Map.class);
        List<LongTermMemoryDraft> memories = new ArrayList<>();
        List<UserProfileItem> profileItems = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        Instant timestamp = createdAt == null ? Instant.now() : createdAt;

        Object rawMemories = root.get("longTermMemories");
        if (rawMemories instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> item)) {
                    continue;
                }
                String category = stringValue(item.get("category"));
                String content = safeMemoryValue(stringValue(item.get("content")));
                double confidence = confidence(item.get("confidence"));
                if (ALLOWED_CATEGORIES.contains(category) && !content.isBlank()
                        && confidence >= 0.55 && dedupe.add(category + "\n" + content)) {
                    memories.add(new LongTermMemoryDraft(category, limit(content, 800), confidence));
                }
            }
        }

        Object rawProfile = root.get("profileItems");
        if (rawProfile instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> item)) {
                    continue;
                }
                String key = normalizeKey(stringValue(item.get("key")));
                String profileValue = safeMemoryValue(stringValue(item.get("value")));
                double confidence = confidence(item.get("confidence"));
                if (!key.isBlank() && !profileValue.isBlank() && confidence >= 0.7
                        && dedupe.add("profile\n" + key + "\n" + profileValue)) {
                    profileItems.add(new UserProfileItem(
                            key,
                            limit(profileValue, 240),
                            "llm-message:" + conversationId + ";confidence=" + confidence,
                            timestamp
                    ));
                }
            }
        }
        return new MemoryExtraction(memories, profileItems);
    }

    private String safeMemoryValue(String value) {
        if (value.isBlank()) {
            return "";
        }
        SensitiveDataFilterResult filtered = sensitiveDataFilter.filter(value);
        return filtered.categories().isEmpty() ? value : filtered.safeContent();
    }

    private String normalizeKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_+", "_");
        return limit(normalized, 64);
    }

    private double confidence(Object value) {
        double parsed = 0;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        }
        else if (value != null) {
            try {
                parsed = Double.parseDouble(String.valueOf(value));
            }
            catch (NumberFormatException ignored) {
                parsed = 0;
            }
        }
        return Math.max(0, Math.min(1, parsed));
    }

    private String extractJson(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        int end = text == null ? -1 : text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("memory extraction output is not JSON");
        }
        return text.substring(start, end + 1);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String limit(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
