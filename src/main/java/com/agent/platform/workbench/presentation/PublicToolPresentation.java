package com.agent.platform.workbench.presentation;

import java.util.Map;

public record PublicToolPresentation(
        String toolName,
        String displayName,
        String actionSummary,
        Map<String, Object> publicArguments,
        String resultSummary,
        Integer resultCount,
        Long durationMs,
        String attemptLabel
) {
    public PublicToolPresentation {
        toolName = text(toolName);
        displayName = text(displayName);
        actionSummary = text(actionSummary);
        publicArguments = publicArguments == null ? Map.of() : Map.copyOf(publicArguments);
        resultSummary = text(resultSummary);
        attemptLabel = text(attemptLabel);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
