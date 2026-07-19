package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.NormalGoalEnvelope;

public record CreateWorkItemCommand(
        String clientInputId,
        String conversationId,
        String content,
        NormalGoalEnvelope goal,
        long expectedFocusVersion
) {

    public CreateWorkItemCommand {
        if (clientInputId == null || clientInputId.isBlank()) {
            throw new IllegalArgumentException("clientInputId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (expectedFocusVersion < 0) {
            throw new IllegalArgumentException("expectedFocusVersion must not be negative");
        }
        clientInputId = clientInputId.trim();
        conversationId = conversationId.trim();
        content = content.trim();
    }
}
