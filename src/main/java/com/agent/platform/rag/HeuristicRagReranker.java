package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HeuristicRagReranker implements RagReranker {

    private final RagProperties ragProperties;

    private final KeywordQueryTokenizer keywordQueryTokenizer;

    public HeuristicRagReranker(RagProperties ragProperties,
                                KeywordQueryTokenizer keywordQueryTokenizer) {
        this.ragProperties = ragProperties;
        this.keywordQueryTokenizer = keywordQueryTokenizer;
    }

    @Override
    public List<RetrievedDocument> rerank(String query, List<RetrievedDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = keywordQueryTokenizer.tokenize(query);
        List<RetrievedDocument> scored = candidates.stream()
                .map(candidate -> scoreCandidate(candidate, queryTokens))
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
        List<RetrievedDocument> ranked = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            RetrievedDocument document = scored.get(index);
            Map<String, Object> metadata = new HashMap<>(document.metadata());
            metadata.put("rank", index + 1);
            metadata.put("rerankRank", index + 1);
            ranked.add(new RetrievedDocument(document.documentId(), document.title(), document.content(), document.score(), metadata));
        }
        return ranked;
    }

    private RetrievedDocument scoreCandidate(RetrievedDocument candidate, List<String> queryTokens) {
        double baseScore = clamp(candidate.score());
        double queryCoverage = queryCoverage(queryTokens, candidate.title() + "\n" + candidate.content());
        double sourceMatch = sourceMatch(queryTokens, String.valueOf(candidate.metadata().getOrDefault("source", candidate.title())));
        RagProperties.Rerank rerank = ragProperties.getRerank();
        double weightSum = rerank.getBaseScoreWeight() + rerank.getQueryCoverageWeight() + rerank.getSourceMatchWeight();
        double rerankScore = weightSum <= 0
                ? baseScore
                : (baseScore * rerank.getBaseScoreWeight()
                + queryCoverage * rerank.getQueryCoverageWeight()
                + sourceMatch * rerank.getSourceMatchWeight()) / weightSum;
        Map<String, Object> metadata = new HashMap<>(candidate.metadata());
        metadata.put("originalScore", candidate.score());
        metadata.put("rerankScore", rerankScore);
        metadata.put("queryCoverage", queryCoverage);
        metadata.put("sourceMatch", sourceMatch);
        metadata.put("reranked", true);
        return new RetrievedDocument(candidate.documentId(), candidate.title(), candidate.content(), rerankScore, metadata);
    }

    private double queryCoverage(List<String> queryTokens, String text) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        String normalizedText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : queryTokens) {
            if (normalizedText.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / queryTokens.size();
    }

    private double sourceMatch(List<String> queryTokens, String source) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedSource.contains(token.toLowerCase(Locale.ROOT))) {
                return 1.0;
            }
        }
        return 0;
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }
}
