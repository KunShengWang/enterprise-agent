package com.agent.platform.eval;

public record AnswerJudgement(
        double score,
        boolean grounded,
        String reason
) {
}
