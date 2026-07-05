package com.agent.platform.eval;

import java.util.List;

public record EvalCase(
        String id,
        String question,
        List<String> expectedKeywords,
        List<String> expectedTools
) {

    public EvalCase {
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
    }
}
