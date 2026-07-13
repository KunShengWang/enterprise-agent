package com.agent.platform.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型摘要不可用时的确定性降级器。
 *
 * <p>它不再拼接整段文本后从中间截断，而是按语义单元选择近期事实；相邻的工具调用和
 * 工具结果会先合并成同一个单元，因此降级路径也不会制造孤立工具结果。</p>
 */
@Component
public class RuleBasedConversationSummarizer implements ConversationSummarizer {

    @Override
    public String summarize(String previousSummary, List<MemoryMessage> messages, int maxChars) {
        int budget = Math.max(400, maxChars);
        List<String> units = buildSemanticUnits(messages == null ? List.of() : messages);
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (int index = units.size() - 1; index >= 0; index--) {
            String unit = units.get(index);
            if (used + unit.length() + 1 > budget && !selected.isEmpty()) {
                break;
            }
            String bounded = boundUnit(unit, Math.max(160, budget - used));
            selected.add(bounded);
            used += bounded.length() + 1;
            if (used >= budget) {
                break;
            }
        }
        Collections.reverse(selected);

        String previous = normalizeWhitespace(previousSummary);
        if (!previous.isBlank()) {
            int remaining = budget - used;
            if (remaining >= 160) {
                selected.add(0, "[既有摘要] " + boundUnit(previous, remaining - 8));
            }
        }
        return String.join("\n", selected);
    }

    private List<String> buildSemanticUnits(List<MemoryMessage> messages) {
        List<String> units = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            MemoryMessage current = messages.get(index);
            if (!usable(current)) {
                continue;
            }
            if ("assistant_tool_call".equals(current.role())
                    && index + 1 < messages.size()
                    && usable(messages.get(index + 1))
                    && "tool_result".equals(messages.get(index + 1).role())) {
                MemoryMessage result = messages.get(++index);
                units.add("[工具交互] 调用=" + normalizeWhitespace(current.content())
                        + " | 结果=" + normalizeWhitespace(result.content()));
                continue;
            }
            units.add("[" + safeRole(current.role()) + "] " + normalizeWhitespace(current.content()));
        }
        return units;
    }

    private boolean usable(MemoryMessage message) {
        return message != null && message.content() != null && !message.content().isBlank();
    }

    private String boundUnit(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (value.startsWith("[工具交互]")) {
            String marker = " | 结果=";
            int split = value.indexOf(marker);
            if (split > 0) {
                int half = Math.max(60, (maxChars - 16) / 2);
                String call = value.substring(0, split);
                String result = value.substring(split + marker.length());
                return suffix(call, half) + marker + suffix(result, half);
            }
        }
        return suffix(value, maxChars);
    }

    private String suffix(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int keep = Math.max(1, maxChars - 1);
        return "…" + value.substring(value.length() - keep);
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                if (!previousWhitespace) {
                    normalized.append(' ');
                }
                previousWhitespace = true;
            }
            else {
                normalized.append(current);
                previousWhitespace = false;
            }
        }
        return normalized.toString().trim();
    }

    private String safeRole(String role) {
        return role == null || role.isBlank() ? "unknown" : role;
    }
}
