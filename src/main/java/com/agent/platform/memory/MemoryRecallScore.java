package com.agent.platform.memory;

import java.util.Set;

public record MemoryRecallScore(
        double score,
        double lexicalScore,// 词汇得分
        double semanticScore,// 语义评分
        Set<String> matchedTerms,// 匹配项
        Set<String> expandedQueryTerms// 扩展查询词
) {

    public MemoryRecallScore {
        matchedTerms = matchedTerms == null ? Set.of() : Set.copyOf(matchedTerms);
        expandedQueryTerms = expandedQueryTerms == null ? Set.of() : Set.copyOf(expandedQueryTerms);
    }
}
