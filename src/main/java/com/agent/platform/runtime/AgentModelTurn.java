package com.agent.platform.runtime;

import com.agent.platform.llm.LlmUsage;

import java.util.List;

public record AgentModelTurn(
        String assistantText,
        List<AgentToolCall> toolCalls,
        String rawResponse,
        LlmUsage usage,
        String finishReason
) {

    public AgentModelTurn {
        assistantText = assistantText == null ? "" : assistantText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        rawResponse = rawResponse == null ? "" : rawResponse;
        finishReason = finishReason == null ? "unknown" : finishReason;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
