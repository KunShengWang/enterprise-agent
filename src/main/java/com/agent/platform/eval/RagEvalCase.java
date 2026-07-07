package com.agent.platform.eval;

import java.util.List;

public record RagEvalCase(
        String id,
        String question,
        int topK,
        List<String> expectedSources,
        List<String> expectedContentKeywords
) {

    public RagEvalCase {
        expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
        expectedContentKeywords = expectedContentKeywords == null ? List.of() : List.copyOf(expectedContentKeywords);
    }

    public int effectiveTopK(int defaultTopK) {
        return topK <= 0 ? defaultTopK : topK;
    }
}
