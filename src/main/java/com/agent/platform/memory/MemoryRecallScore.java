package com.agent.platform.memory;

import java.util.Set;

public record MemoryRecallScore(
        double score,
        double lexicalScore,
        double semanticScore,
        Set<String> matchedTerms,
        Set<String> expandedQueryTerms
) {

    public MemoryRecallScore {
        matchedTerms = matchedTerms == null ? Set.of() : Set.copyOf(matchedTerms);
        expandedQueryTerms = expandedQueryTerms == null ? Set.of() : Set.copyOf(expandedQueryTerms);
    }
}
