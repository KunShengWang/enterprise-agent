package com.agent.platform.eval;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentStep;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DefaultAgentEvalRunner implements EvalRunner {

    private final AgentExecutor agentExecutor;

    private final EvalCaseRepository evalCaseRepository;

    private final AnswerJudge answerJudge;

    public DefaultAgentEvalRunner(AgentExecutor agentExecutor,
                                  EvalCaseRepository evalCaseRepository,
                                  AnswerJudge answerJudge) {
        this.agentExecutor = agentExecutor;
        this.evalCaseRepository = evalCaseRepository;
        this.answerJudge = answerJudge;
    }

    @Override
    public EvalReport run(List<EvalCase> evalCases) {
        List<EvalCase> effectiveCases = evalCases == null || evalCases.isEmpty() ? evalCaseRepository.list() : List.copyOf(evalCases);
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : effectiveCases) {
            results.add(runCase(evalCase));
        }
        int totalCases = results.size();
        int passedCases = (int) results.stream().filter(EvalCaseResult::passed).count();
        double averageScore = average(results.stream().map(EvalCaseResult::score).toList());
        double keywordHitRate = rate(results.stream().filter(EvalCaseResult::keywordHit).count(), totalCases);
        double toolCallSuccessRate = rate(results.stream().filter(EvalCaseResult::toolCallMatched).count(), totalCases);
        double ragUsageAccuracy = rate(results.stream().filter(EvalCaseResult::ragMatched).count(), totalCases);
        double groundednessRate = rate(results.stream().filter(EvalCaseResult::grounded).count(), totalCases);
        return new EvalReport(
                UUID.randomUUID().toString(),
                Instant.now(),
                totalCases,
                passedCases,
                rate(passedCases, totalCases),
                averageScore,
                keywordHitRate,
                toolCallSuccessRate,
                ragUsageAccuracy,
                groundednessRate,
                results
        );
    }

    private EvalCaseResult runCase(EvalCase evalCase) {
        AgentResponse response = agentExecutor.execute(new AgentRequest(
                "eval-" + evalCase.id(),
                "eval-user",
                evalCase.question(),
                Map.of("evalCaseId", evalCase.id(), "eval", true)
        ));
        String answer = response.answer() == null ? "" : response.answer();
        List<String> actualTools = actualTools(response);
        List<String> missingKeywords = missingKeywords(answer, evalCase.expectedKeywords());
        List<String> forbiddenHits = forbiddenHits(answer, evalCase.forbiddenKeywords());
        boolean keywordHit = missingKeywords.isEmpty();
        boolean toolMatched = toolMatched(evalCase, actualTools);
        boolean ragMatched = ragMatched(evalCase, response);
        AnswerJudgement judgement = answerJudge.judge(evalCase, response);
        double keywordScore = evalCase.expectedKeywords().isEmpty() ? 1.0 :
                (double) (evalCase.expectedKeywords().size() - missingKeywords.size()) / evalCase.expectedKeywords().size();
        double toolScore = toolMatched ? 1.0 : 0.0;
        double ragScore = ragMatched ? 1.0 : 0.0;
        double groundednessScore = judgement.grounded() ? 1.0 : 0.0;
        double forbiddenPenalty = forbiddenHits.isEmpty() ? 0.0 : 0.25;
        double score = Math.max(0, Math.min(1,
                (keywordScore * 0.3)
                        + (toolScore * 0.2)
                        + (ragScore * 0.2)
                        + (groundednessScore * 0.2)
                        + (judgement.score() * 0.1)
                        - forbiddenPenalty
        ));
        boolean passed = score >= evalCase.minScore()
                && keywordHit
                && toolMatched
                && ragMatched
                && forbiddenHits.isEmpty()
                && judgement.grounded();
        return new EvalCaseResult(
                evalCase.id(),
                evalCase.question(),
                answer,
                passed,
                score,
                keywordScore,
                toolScore,
                ragScore,
                groundednessScore,
                keywordHit,
                toolMatched,
                ragMatched,
                judgement.grounded(),
                actualTools,
                missingKeywords,
                forbiddenHits,
                judgement.reason(),
                response.trace().traceId(),
                Map.of("status", response.status().name(), "minScore", evalCase.minScore())
        );
    }

    private List<String> actualTools(AgentResponse response) {
        Set<String> tools = new LinkedHashSet<>();
        for (AgentStep step : response.steps()) {
            if ("tool.plan".equals(step.name()) && step.summary().contains("tool=")) {
                String summary = step.summary();
                int start = summary.indexOf("tool=") + "tool=".length();
                int end = summary.indexOf(",", start);
                tools.add(end > start ? summary.substring(start, end).trim() : summary.substring(start).trim());
            }
            if ("tool.execute".equals(step.name())) {
                String summary = step.summary();
                int marker = summary.indexOf("\"toolName\"");
                if (marker >= 0) {
                    tools.add(summary.substring(marker));
                }
            }
        }
        return List.copyOf(tools);
    }

    private boolean toolMatched(EvalCase evalCase, List<String> actualTools) {
        if (!evalCase.expectToolCall() && evalCase.expectedTools().isEmpty()) {
            return actualTools.isEmpty();
        }
        if (evalCase.expectedTools().isEmpty()) {
            return !actualTools.isEmpty();
        }
        for (String expectedTool : evalCase.expectedTools()) {
            boolean hit = actualTools.stream().anyMatch(tool -> tool.contains(expectedTool));
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private boolean ragMatched(EvalCase evalCase, AgentResponse response) {
        boolean usedRag = response.steps().stream().anyMatch(step -> "rag.retrieve".equals(step.name()));
        if (!evalCase.expectRag()) {
            return !usedRag;
        }
        return response.steps().stream()
                .anyMatch(step -> "rag.retrieve".equals(step.name()) && ("HIT".equalsIgnoreCase(step.status()) || step.summary().contains("documents=")));
    }

    private List<String> missingKeywords(String answer, List<String> expectedKeywords) {
        List<String> missing = new ArrayList<>();
        for (String keyword : expectedKeywords) {
            if (keyword != null && !keyword.isBlank() && !answer.contains(keyword)) {
                missing.add(keyword);
            }
        }
        return missing;
    }

    private List<String> forbiddenHits(String answer, List<String> forbiddenKeywords) {
        List<String> hits = new ArrayList<>();
        for (String keyword : forbiddenKeywords) {
            if (keyword != null && !keyword.isBlank() && answer.contains(keyword)) {
                hits.add(keyword);
            }
        }
        return hits;
    }

    private double average(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double rate(long passed, long total) {
        return total <= 0 ? 0 : (double) passed / total;
    }
}
