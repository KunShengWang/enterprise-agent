package com.agent.platform.prompt;

import java.util.List;
import java.util.Map;

public record PromptRequest(
        String systemPrompt,
        String userPrompt,
        List<String> contextBlocks,
        Map<String, Object> metadata
) {

    public PromptRequest {
        contextBlocks = contextBlocks == null ? List.of() : List.copyOf(contextBlocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
