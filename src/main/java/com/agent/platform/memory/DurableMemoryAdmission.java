package com.agent.platform.memory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Small deterministic admission boundary shared by extraction and persistence.
 */
final class DurableMemoryAdmission {

    static final double MIN_LONG_TERM_CONFIDENCE = 0.55;
    static final double MIN_PROFILE_CONFIDENCE = 0.70;
    static final int MAX_LONG_TERM_CONTENT_LENGTH = 800;
    static final int MAX_PROFILE_VALUE_LENGTH = 240;

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

    private DurableMemoryAdmission() {
    }

    static boolean hasStableUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String normalized = userId.trim();
        return !normalized.equalsIgnoreCase("anonymous")
                && !normalized.equalsIgnoreCase("anonymous-user");
    }

    static boolean allowsAutomaticExtraction(String content) {
        return hasDurableIntent(content) && !hasEphemeralCue(content);
    }

    static boolean allowsCandidateContent(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank()
                && !hasEphemeralCue(content)
                && !containsAny(normalized, DYNAMIC_PROCUREMENT_FACT_CUES);
    }

    static boolean isExactSourceSpan(String originalContent, String candidate, int maxLength) {
        if (originalContent == null || candidate == null) {
            return false;
        }
        String trimmed = candidate.trim();
        return !trimmed.isBlank()
                && trimmed.length() <= maxLength
                && originalContent.contains(trimmed);
    }

    static String automaticProfileSource(String conversationId, Instant createdAt) {
        return "llm-message:" + conversationId + ";createdAt=" + createdAt;
    }

    private static boolean hasDurableIntent(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank() && containsAny(normalized, DURABLE_INTENT_CUES);
    }

    private static boolean hasEphemeralCue(String content) {
        String normalized = normalizeForCueMatching(content);
        return !normalized.isBlank() && containsAny(normalized, EPHEMERAL_CUES);
    }

    private static String normalizeForCueMatching(String content) {
        return content == null ? "" : content.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String content, List<String> cues) {
        return cues.stream().anyMatch(content::contains);
    }
}
