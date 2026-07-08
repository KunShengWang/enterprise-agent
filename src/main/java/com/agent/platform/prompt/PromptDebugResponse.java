package com.agent.platform.prompt;

import java.util.Map;

public record PromptDebugResponse(
        String conversationId,
        String routeType,
        String routeReason,
        String rewrittenQuery,
        String plannedToolName,
        Map<String, Object> plannedToolArguments,
        boolean ragRetrieved,
        PromptRequest prompt,
        String fullPrompt,
        Map<String, Object> metadata
) {

    public PromptDebugResponse {
        plannedToolArguments = plannedToolArguments == null ? Map.of() : Map.copyOf(plannedToolArguments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
