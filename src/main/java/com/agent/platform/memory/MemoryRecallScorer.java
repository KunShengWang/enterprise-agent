package com.agent.platform.memory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class MemoryRecallScorer {

    public double score(String query, String content) {
        if (isBlank(query) || isBlank(content)) {
            return 0;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return normalizedContent.contains(query.toLowerCase(Locale.ROOT)) ? 1.0 : 0;
        }
        int hits = 0;
        for (String token : tokens) {
            if (normalizedContent.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (containsHan(part) && part.length() > 6) {
                for (int index = 0; index < part.length() - 1; index++) {
                    tokens.add(part.substring(index, index + 2));
                }
            }
            else {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
