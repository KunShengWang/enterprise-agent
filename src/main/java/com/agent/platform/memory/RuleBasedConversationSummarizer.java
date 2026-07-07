package com.agent.platform.memory;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleBasedConversationSummarizer implements ConversationSummarizer {

    @Override
    public String summarize(String previousSummary, List<MemoryMessage> messages, int maxChars) {
        StringBuilder builder = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            builder.append(previousSummary.trim()).append('\n');
        }
        for (MemoryMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            builder.append("- ")
                    .append(message.role())
                    .append(": ")
                    .append(compact(message.content(), 180))
                    .append('\n');
        }
        return trimToMaxChars(builder.toString().trim(), Math.max(200, maxChars));
    }

    private String compact(String content, int maxChars) {
        String compacted = content.replaceAll("\\s+", " ").trim();
        return trimToMaxChars(compacted, maxChars);
    }

    private String trimToMaxChars(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(Math.max(0, value.length() - maxChars));
    }
}
