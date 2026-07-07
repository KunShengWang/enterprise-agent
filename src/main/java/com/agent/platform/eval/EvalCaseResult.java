package com.agent.platform.eval;

import java.util.List;
import java.util.Map;

public record EvalCaseResult(
        String caseId,
        String question,
        String answer,
        boolean passed,
        double score,
        double keywordScore,
        double toolScore,
        double ragScore,
        double groundednessScore,
        boolean keywordHit,
        boolean toolCallMatched,
        boolean ragMatched,
        boolean grounded,
        List<String> actualTools,
        List<String> missingKeywords,
        List<String> forbiddenKeywordHits,
        String judgeReason,
        String traceId,
        Map<String, Object> metadata
) {

    public EvalCaseResult {
        actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
        missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
        forbiddenKeywordHits = forbiddenKeywordHits == null ? List.of() : List.copyOf(forbiddenKeywordHits);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
