package com.agent.platform.eval;

import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentStep;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HeuristicAnswerJudge implements AnswerJudge {

    @Override
    public AnswerJudgement judge(EvalCase evalCase, AgentResponse response) {
        String answer = response == null || response.answer() == null ? "" : response.answer();
        double keywordScore = keywordScore(answer, evalCase.expectedKeywords());
        boolean grounded = grounded(evalCase, response);
        double groundednessScore = grounded ? 1.0 : 0.0;
        double score = (keywordScore * 0.65) + (groundednessScore * 0.35);
        String reason = "heuristic judge: keywordScore=" + keywordScore + ", grounded=" + grounded;
        return new AnswerJudgement(score, grounded, reason);
    }

    private double keywordScore(String answer, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 1.0;
        }
        int hits = 0;
        for (String keyword : expectedKeywords) {
            if (keyword != null && !keyword.isBlank() && answer.contains(keyword)) {
                hits++;
            }
        }
        return (double) hits / expectedKeywords.size();
    }

    private boolean grounded(EvalCase evalCase, AgentResponse response) {
        if (response == null) {
            return false;
        }
        if (evalCase.expectRag()) {
            return response.steps().stream()
                    .anyMatch(step -> "rag.retrieve".equals(step.name()) && ("HIT".equalsIgnoreCase(step.status()) || step.summary().contains("documents=")));
        }
        if (evalCase.expectToolCall()) {
            return response.steps().stream()
                    .anyMatch(step -> "tool.execute".equals(step.name()) && "COMPLETED".equalsIgnoreCase(step.status()));
        }
        return response.steps().stream()
                .map(AgentStep::name)
                .noneMatch(name -> name.startsWith("tool.execute") || name.startsWith("rag.retrieve"));
    }
}
