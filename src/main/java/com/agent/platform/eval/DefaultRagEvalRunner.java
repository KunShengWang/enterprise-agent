package com.agent.platform.eval;

import com.agent.platform.config.RagProperties;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.rag.RetrievedDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DefaultRagEvalRunner implements RagEvalRunner {

    private final RagProperties ragProperties;

    private final RagService ragService;

    public DefaultRagEvalRunner(RagProperties ragProperties, RagService ragService) {
        this.ragProperties = ragProperties;
        this.ragService = ragService;
    }

    @Override
    public RagEvalReport run(List<RagEvalCase> cases) {
        List<RagEvalCase> effectiveCases = cases == null || cases.isEmpty() ? defaultCases() : List.copyOf(cases);
        long totalStartNanos = System.nanoTime();
        List<RagEvalCaseResult> results = new ArrayList<>();
        for (RagEvalCase evalCase : effectiveCases) {
            results.add(runCase(evalCase));
        }
        int passedCases = (int) results.stream().filter(RagEvalCaseResult::passed).count();
        double totalCases = results.size();
        double averageScore = results.stream().mapToDouble(RagEvalCaseResult::score).average().orElse(0);
        double sourceHitRate = results.stream().filter(RagEvalCaseResult::sourceHit).count() / totalCases;
        double keywordHitRate = results.stream().filter(RagEvalCaseResult::keywordHit).count() / totalCases;
        return new RagEvalReport(
                results.size(),
                passedCases,
                totalCases == 0 ? 0 : passedCases / totalCases,
                averageScore,
                sourceHitRate,
                keywordHitRate,
                elapsedMs(totalStartNanos),
                results
        );
    }

    private RagEvalCaseResult runCase(RagEvalCase evalCase) {
        long startNanos = System.nanoTime();
        RagResult ragResult = ragService.retrieve(evalCase.question(), evalCase.effectiveTopK(ragProperties.getTopK()));
        Set<String> foundSources = foundSources(ragResult.documents());
        Set<String> foundKeywords = foundKeywords(ragResult.documents(), evalCase.expectedContentKeywords());
        boolean sourceHit = evalCase.expectedSources().isEmpty() || containsAny(foundSources, evalCase.expectedSources());
        boolean keywordHit = evalCase.expectedContentKeywords().isEmpty() || foundKeywords.containsAll(evalCase.expectedContentKeywords());
        double sourceScore = sourceHit ? 1.0 : 0.0;
        double keywordScore = evalCase.expectedContentKeywords().isEmpty()
                ? 1.0
                : (double) foundKeywords.size() / evalCase.expectedContentKeywords().size();
        double score = 0.7 * sourceScore + 0.3 * keywordScore;
        boolean passed = sourceHit && keywordHit;
        return new RagEvalCaseResult(
                evalCase.id(),
                evalCase.question(),
                passed,
                score,
                sourceHit,
                keywordHit,
                ragResult.documents().size(),
                evalCase.expectedSources(),
                List.copyOf(foundSources),
                evalCase.expectedContentKeywords(),
                List.copyOf(foundKeywords),
                elapsedMs(startNanos)
        );
    }

    private Set<String> foundSources(List<RetrievedDocument> documents) {
        Set<String> sources = new LinkedHashSet<>();
        for (RetrievedDocument document : documents) {
            Object source = document.metadata().getOrDefault("source", document.title());
            if (source != null) {
                sources.add(String.valueOf(source));
            }
        }
        return sources;
    }

    private Set<String> foundKeywords(List<RetrievedDocument> documents, List<String> expectedKeywords) {
        Set<String> found = new LinkedHashSet<>();
        StringBuilder joinedContent = new StringBuilder();
        for (RetrievedDocument document : documents) {
            joinedContent.append(document.title()).append('\n').append(document.content()).append('\n');
        }
        String normalizedContent = joinedContent.toString().toLowerCase(Locale.ROOT);
        for (String keyword : expectedKeywords) {
            if (keyword != null && normalizedContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                found.add(keyword);
            }
        }
        return found;
    }

    private boolean containsAny(Set<String> foundValues, List<String> expectedValues) {
        for (String expectedValue : expectedValues) {
            if (foundValues.contains(expectedValue)) {
                return true;
            }
        }
        return false;
    }

    private List<RagEvalCase> defaultCases() {
        return List.of(
                new RagEvalCase("rag-refund-policy", "退款审批流程是什么？", 3, List.of("refund-policy.md"), List.of("客服主管", "财务复核")),
                new RagEvalCase("rag-incident-response", "P1 故障应该怎么响应？", 3, List.of("incident-response.md"), List.of("10 分钟", "复盘报告")),
                new RagEvalCase("rag-release-process", "高风险生产发布需要哪些准备？", 3, List.of("release-process.md"), List.of("回滚预案", "灰度验证")),
                new RagEvalCase("rag-flow", "RAG 的主流程是什么？", 3, List.of("rag-guide.md"), List.of("Embedding", "TopK"))
        );
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
