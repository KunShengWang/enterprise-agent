package com.agent.platform.agent;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record AgentRequest(
        String conversationId,
        String userId,
        @NotBlank(message = "question must not be blank")
        String question,
        Map<String, Object> metadata
) {

    public AgentRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
