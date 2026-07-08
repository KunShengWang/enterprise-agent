package com.agent.platform.llm;

public record LlmUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens,
        String model,
        String source
) {

    public boolean hasTokenUsage() {
        return promptTokens > 0 || completionTokens > 0 || totalTokens > 0;
    }
}
