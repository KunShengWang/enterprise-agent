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

    private static final Set<String> AUTOMATIC_PROFILE_KEYS = Set.of("language", "response_style");
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
        if (!DurableMemoryAdmission.hasStableUserId(userId)
                || message == null || !"user".equalsIgnoreCase(message.role())
                || message.content() == null || message.content().isBlank()) {
            return MemoryExtraction.empty();
        }
        String originalUserContent = message.content();
        String content = originalUserContent.trim();
        if (!DurableMemoryAdmission.allowsAutomaticExtraction(content)) {
            return MemoryExtraction.empty();
        }
        try {
            String raw = llmService.complete(new PromptRequest(
                    """
                    你是长期记忆提取器，只输出 JSON，不要 Markdown。
                    仅提取用户明确表达、未来跨任务仍有价值的软偏好或稳定交互指令；不要保存临时闲聊、模型回答或未经确认的推测。
                    只能返回 type=PREFERENCE 或 type=STABLE_INSTRUCTION。
                    当前预算、deadline、数量、排除或选中的供应商、报价、库存、规格、采购/订单状态、一次性决策和 ToolResult 动态事实都禁止作为长期记忆。
                    禁止提取密码、令牌、身份证、手机号、银行卡等敏感数据；检测到敏感信息时不要返回该项。
                    content 和 profile value 必须逐字复制用户原始消息中的连续原文片段；不得总结、翻译、改写、规范化或补充用户没有说过的内容。
                    自动 profile key 只能是 language 或 response_style。
                    JSON：{"longTermMemories":[{"type":"PREFERENCE","content":"用户消息中的原文片段","confidence":0.0}],"profileItems":[{"key":"language","value":"用户消息中的原文片段","confidence":0.0}]}
                    没有可保存内容时返回两个空数组。
                    """.strip(),
                    "用户消息：" + content,
                    List.of("conversationId=" + conversationId, "userId=" + userId),
                    Map.of("purpose", "memory_extraction")
            ));
            return parseExtraction(raw, originalUserContent, conversationId, message.createdAt());
        }
        catch (RuntimeException exception) {
            // Extraction is a persistence decision. On model/protocol failure, do not guess and write false memory.
            return MemoryExtraction.empty();
        }
    }

    private MemoryExtraction parseExtraction(String raw,
                                             String originalUserContent,
                                             String conversationId,
                                             Instant createdAt) {
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
                DurableMemoryType type = typeValue(item.get("type"));
                String content = safeMemoryValue(stringValue(item.get("content")));
                Double confidence = confidence(item.get("confidence"));
                if (type != null
                        && DurableMemoryAdmission.allowsCandidateContent(content)
                        && DurableMemoryAdmission.isExactSourceSpan(
                        originalUserContent, content, DurableMemoryAdmission.MAX_LONG_TERM_CONTENT_LENGTH)
                        && confidence != null && confidence >= DurableMemoryAdmission.MIN_LONG_TERM_CONFIDENCE
                        && dedupe.add(type.name() + "\n" + content)) {
                    memories.add(new LongTermMemoryDraft(type, content, confidence));
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
                Double confidence = confidence(item.get("confidence"));
                if (AUTOMATIC_PROFILE_KEYS.contains(key)
                        && DurableMemoryAdmission.allowsCandidateContent(profileValue)
                        && DurableMemoryAdmission.isExactSourceSpan(
                        originalUserContent, profileValue, DurableMemoryAdmission.MAX_PROFILE_VALUE_LENGTH)
                        && confidence != null && confidence >= DurableMemoryAdmission.MIN_PROFILE_CONFIDENCE
                        && dedupe.add("profile\n" + key + "\n" + profileValue)) {
                    profileItems.add(new UserProfileItem(
                            key,
                            profileValue,
                            DurableMemoryAdmission.automaticProfileSource(conversationId, timestamp),
                            timestamp
                    ));
                }
            }
        }
        return new MemoryExtraction(memories, profileItems);
    }

    private String safeMemoryValue(String value) {
        if (value == null || value.isBlank() || sensitiveDataFilter == null) {
            return "";
        }
        SensitiveDataFilterResult filtered = sensitiveDataFilter.filter(value);
        if (filtered == null || !filtered.categories().isEmpty()) {
            return "";
        }
        return filtered.safeContent() == null ? "" : filtered.safeContent().trim();
    }

    private String normalizeKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_+", "_");
        return limit(normalized, 64);
    }

    private DurableMemoryType typeValue(Object value) {
        return value instanceof String text
                ? DurableMemoryType.fromProtocolValue(text).orElse(null) : null;
    }

    private Double confidence(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double parsed = number.doubleValue();
        return Double.isFinite(parsed) && parsed >= 0 && parsed <= 1 ? parsed : null;
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
        return value instanceof String text ? text.trim() : "";
    }

    private String limit(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

}
