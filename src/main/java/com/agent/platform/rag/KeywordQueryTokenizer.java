package com.agent.platform.rag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class KeywordQueryTokenizer {

    private static final int MAX_TOKENS = 12;

    public List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder latinOrDigit = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int index = 0; index < query.length(); index++) {
            char current = query.charAt(index);
            if (isHan(current)) {
                flushLatinOrDigit(tokens, latinOrDigit);
                han.append(current);
            }
            else if (Character.isLetterOrDigit(current)) {
                flushHan(tokens, han);
                latinOrDigit.append(Character.toLowerCase(current));
            }
            else {
                flushLatinOrDigit(tokens, latinOrDigit);
                flushHan(tokens, han);
            }
        }
        flushLatinOrDigit(tokens, latinOrDigit);
        flushHan(tokens, han);
        return tokens.stream()
                .filter(token -> !token.isBlank())
                .limit(MAX_TOKENS)
                .toList();
    }

    private void flushLatinOrDigit(Set<String> tokens, StringBuilder builder) {
        if (builder.length() >= 2) {
            tokens.add(builder.toString().toLowerCase(Locale.ROOT));
        }
        builder.setLength(0);
    }

    private void flushHan(Set<String> tokens, StringBuilder builder) {
        if (builder.length() == 1) {
            builder.setLength(0);
            return;
        }
        if (builder.length() <= 12) {
            tokens.add(builder.toString());
        }
        for (int index = 0; index < builder.length() - 1; index++) {
            tokens.add(builder.substring(index, index + 2));
        }
        builder.setLength(0);
    }

    private boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }
}
