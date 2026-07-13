package com.agent.platform.memory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 无 Embedding 时的词法降级评分器。
 *
 * <p>它不再用人工同义词表伪装语义能力。正式长期记忆召回由数据库中保存的
 * Embedding 余弦相似度主导，本类只为摘要、旧消息和 Embedding 故障提供降级。</p>
 */
@Component
public class MemoryRecallScorer {

    public double score(String query, String content) {
        return scoreDetail(query, content).score();
    }

    public MemoryRecallScore scoreDetail(String query, String content) {
        if (isBlank(query) || isBlank(content)) {
            return new MemoryRecallScore(0, 0, 0, Set.of(), Set.of());
        }
        String normalizedContent = normalize(content);
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            double exact = normalizedContent.contains(normalize(query)) ? 1.0 : 0;
            return new MemoryRecallScore(exact, exact, 0, Set.of(), Set.of());
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String token : tokens) {
            if (normalizedContent.contains(token)) {
                matched.add(token);
            }
        }
        double lexical = (double) matched.size() / tokens.size();
        return new MemoryRecallScore(lexical, lexical, 0, matched, Set.of());
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = normalize(value).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (containsHan(part) && part.length() > 4) {
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

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
