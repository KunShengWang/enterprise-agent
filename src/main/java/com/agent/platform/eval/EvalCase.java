package com.agent.platform.eval;

import java.util.List;
import java.util.Map;

public record EvalCase(
        String id,
        String name,
        String question,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords,
        List<String> expectedTools,
        boolean expectRag,
        boolean expectToolCall,
        double minScore,
        Map<String, Object> metadata
) {

    public EvalCase {
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        forbiddenKeywords = forbiddenKeywords == null ? List.of() : List.copyOf(forbiddenKeywords);
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        minScore = minScore <= 0 ? 0.7 : minScore;
    }

    public EvalCase(String id, String question, List<String> expectedKeywords, List<String> expectedTools) {
        this(id, id, question, expectedKeywords, List.of(), expectedTools, false, !expectedTools.isEmpty(), 0.7, Map.of());
    }
}
