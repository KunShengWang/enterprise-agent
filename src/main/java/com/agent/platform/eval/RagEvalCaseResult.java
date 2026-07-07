package com.agent.platform.eval;

import java.util.List;

public record RagEvalCaseResult(
        String id,
        String question,
        boolean passed,
        double score,
        boolean sourceHit,
        boolean keywordHit,
        int retrievedDocuments,
        List<String> expectedSources,
        List<String> foundSources,
        List<String> expectedContentKeywords,
        List<String> foundContentKeywords,
        long durationMs
) {

    public RagEvalCaseResult {
        expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
        foundSources = foundSources == null ? List.of() : List.copyOf(foundSources);
        expectedContentKeywords = expectedContentKeywords == null ? List.of() : List.copyOf(expectedContentKeywords);
        foundContentKeywords = foundContentKeywords == null ? List.of() : List.copyOf(foundContentKeywords);
    }
}
