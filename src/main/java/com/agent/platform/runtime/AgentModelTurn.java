package com.agent.platform.runtime;

import com.agent.platform.llm.LlmUsage;

import java.util.List;

public record AgentModelTurn(
        String assistantText,
        List<AgentToolCall> toolCalls,
        String rawResponse,
        LlmUsage usage,
        String finishReason,
        String reasoningContent
) {

    public AgentModelTurn(String assistantText,
                          List<AgentToolCall> toolCalls,
                          String rawResponse,
                          LlmUsage usage,
                          String finishReason) {
        this(assistantText, toolCalls, rawResponse, usage, finishReason, "");
    }

    public AgentModelTurn {
        assistantText = assistantText == null ? "" : assistantText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        rawResponse = rawResponse == null ? "" : rawResponse;
        finishReason = finishReason == null ? "unknown" : finishReason;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
