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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通过 JSON Schema 风格协议提取长期记忆和用户画像，并进行脱敏、去重和置信度校验。
 */
@Primary
@Service
public class LlmMemoryExtractor implements MemoryExtractor {

    private static final Set<String> AUTOMATIC_PROFILE_KEYS = Set.of("language", "response_style");
    private static final List<String> DURABLE_INTENT_CUES = List.of(
            "以后", "今后", "默认", "通常", "一直", "长期", "我的偏好", "我更喜欢", "我偏好",
            "请记住", "记住", "每次", "总是", "always", "from now on", "going forward",
            "by default", "usually", "my preference", "i prefer", "remember", "long-term"
    );
    private static final List<String> EPHEMERAL_CUES = List.of(
            "这次", "本次", "当前", "今天", "本周", "这个项目", "本项目", "这单", "本单",
            "这笔", "本采购", "这个采购", "这个订单", "本订单", "当前任务", "这项任务", "临时", "暂时",
            "this time", "current", "today", "this week", "this project", "this task", "this case",
            "this order", "this purchase", "for now", "temporarily", "temporary", "one-off"
    );
    private static final List<String> DYNAMIC_PROCUREMENT_FACT_CUES = List.of(
            "预算", "报价", "供应商", "库存", "数量", "交期", "supplier", "quote", "inventory",
            "quantity", "deadline", "lead time", "selected supplier", "excluded supplier",
            "采购状态", "订单状态", "case state", "toolresult"
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
        String content = message.content().trim();
        if (!allowsAutomaticExtraction(content)) {
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
                    每个长期记忆项必须包含 durableIntent=true 和 ephemeral=false；每个 profile 项也必须包含这两个布尔字段。
                    自动 profile key 只能是 language 或 response_style。
                    JSON：{"longTermMemories":[{"type":"PREFERENCE","content":"内容","confidence":0.0,"durableIntent":true,"ephemeral":false}],"profileItems":[{"key":"language","value":"中文","confidence":0.0,"durableIntent":true,"ephemeral":false}]}
                    没有可保存内容时返回两个空数组。
                    """.strip(),
                    "用户消息：" + content,
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
                DurableMemoryType type = typeValue(item.get("type"));
                String content = safeMemoryValue(stringValue(item.get("content")));
                Double confidence = confidence(item.get("confidence"));
                if (type != null && allowsCandidateContent(content)
                        && Boolean.TRUE.equals(booleanValue(item.get("durableIntent")))
                        && Boolean.FALSE.equals(booleanValue(item.get("ephemeral")))
                        && confidence != null && confidence >= 0.55
                        && dedupe.add(type.name() + "\n" + content)) {
                    memories.add(new LongTermMemoryDraft(type, limit(content, 800), confidence));
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
                if (AUTOMATIC_PROFILE_KEYS.contains(key) && !profileValue.isBlank()
                        && Boolean.TRUE.equals(booleanValue(item.get("durableIntent")))
                        && Boolean.FALSE.equals(booleanValue(item.get("ephemeral")))
                        && confidence != null && confidence >= 0.7
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

    static boolean allowsAutomaticExtraction(String content) {
        return hasDurableIntent(content) && !hasEphemeralCue(content);
    }

    static boolean hasDurableIntent(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank() && containsAny(normalized, DURABLE_INTENT_CUES);
    }

    static boolean hasEphemeralCue(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank() && containsAny(normalized, EPHEMERAL_CUES);
    }

    static boolean allowsCandidateContent(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank() && !hasEphemeralCue(normalized)
                && !containsAny(normalized, DYNAMIC_PROCUREMENT_FACT_CUES);
    }

    private static String normalizeForCueMatching(String content) {
        return content == null ? "" : content.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String content, List<String> cues) {
        return cues.stream().anyMatch(content::contains);
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

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
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
