package com.agent.platform.agent;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record AgentRequest(
        String conversationId,
        String userId,
        @NotBlank(message = "question must not be blank")
        String question,
        Map<String, Object> metadata,
        String scenarioId
) {

    public AgentRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        scenarioId = scenarioId == null ? "" : scenarioId.trim();
    }

    public AgentRequest(String conversationId,
                        String userId,
                        String question,
                        Map<String, Object> metadata) {
        this(conversationId, userId, question, metadata, "");
    }
}
