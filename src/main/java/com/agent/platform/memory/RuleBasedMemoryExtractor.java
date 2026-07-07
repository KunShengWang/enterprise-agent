package com.agent.platform.memory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuleBasedMemoryExtractor implements MemoryExtractor {

    private static final Pattern NAME_PATTERN = Pattern.compile("我叫([^，。,.!！?？\\s]{1,20})");

    private static final Pattern IDENTITY_PATTERN = Pattern.compile("我是([^，。,.!！?？]{2,40})");

    private static final Pattern MY_FIELD_PATTERN = Pattern.compile("我的([^，。,.!！?？\\s]{1,12})是([^，。,.!！?？]{1,80})");

    @Override
    public MemoryExtraction extract(String conversationId, String userId, MemoryMessage message) {
        if (message == null || !"user".equalsIgnoreCase(message.role()) || isBlank(message.content())) {
            return MemoryExtraction.empty();
        }
        String content = message.content().trim();
        Instant now = message.createdAt() == null ? Instant.now() : message.createdAt();
        List<LongTermMemoryDraft> longTermMemories = new ArrayList<>();
        List<UserProfileItem> profileItems = new ArrayList<>();

        match(NAME_PATTERN, content)
                .forEach(value -> profileItems.add(new UserProfileItem("name", value, "message:" + conversationId, now)));
        match(IDENTITY_PATTERN, content)
                .forEach(value -> profileItems.add(new UserProfileItem("identity", value, "message:" + conversationId, now)));
        Matcher fieldMatcher = MY_FIELD_PATTERN.matcher(content);
        while (fieldMatcher.find()) {
            String key = normalizeProfileKey(fieldMatcher.group(1));
            String value = fieldMatcher.group(2).trim();
            if (!key.isBlank() && !value.isBlank()) {
                profileItems.add(new UserProfileItem(key, value, "message:" + conversationId, now));
            }
        }
        if (content.contains("我喜欢")) {
            profileItems.add(new UserProfileItem("preference", after(content, "我喜欢"), "message:" + conversationId, now));
        }
        if (content.contains("我不喜欢")) {
            profileItems.add(new UserProfileItem("negative_preference", after(content, "我不喜欢"), "message:" + conversationId, now));
        }
        if (content.contains("记住")) {
            String fact = after(content, "记住");
            profileItems.add(new UserProfileItem("instruction", fact, "message:" + conversationId, now));
            longTermMemories.add(new LongTermMemoryDraft("instruction", fact, 0.95));
        }
        if (content.contains("以后")) {
            longTermMemories.add(new LongTermMemoryDraft("preference_or_instruction", content, 0.85));
        }
        if (isBusinessFact(content)) {
            longTermMemories.add(new LongTermMemoryDraft("business_fact", content, 0.8));
        }
        return new MemoryExtraction(longTermMemories, profileItems);
    }

    private List<String> match(Pattern pattern, String content) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String normalizeProfileKey(String key) {
        return key.trim()
                .replace("名字", "name")
                .replace("姓名", "name")
                .replace("角色", "role")
                .replace("岗位", "job")
                .replace("部门", "department")
                .replace("偏好", "preference");
    }

    private String after(String content, String marker) {
        int index = content.indexOf(marker);
        if (index < 0) {
            return content;
        }
        String value = content.substring(index + marker.length())
                .replaceFirst("^[：:，,\\s]+", "")
                .trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private boolean isBusinessFact(String content) {
        return containsAny(content, List.of("工单", "故障", "系统", "服务", "数据库", "Redis", "PostgreSQL", "MCP", "RAG", "Agent"))
                && containsAny(content, List.of("是", "编号", "状态", "原因", "规则", "流程", "负责人", "优先级"));
    }

    private boolean containsAny(String content, List<String> keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
