package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.WorkRelationType;

/** Request-body-safe command: identity and server-generated IDs are intentionally absent. */
public record SubmitWorkInputCommand(
        String clientInputId,
        String conversationId,
        String content,
        String goalText,
        GoalOrigin goalOrigin,
        String commandDecisionId,
        String parentWorkItemId,
        WorkRelationType relationType,
        long expectedFocusVersion
) {

    public SubmitWorkInputCommand {
        clientInputId = requireText(clientInputId, "clientInputId");
        conversationId = requireText(conversationId, "conversationId");
        content = requireText(content, "content");
        goalText = requireText(goalText, "goalText");
        goalOrigin = goalOrigin == null ? GoalOrigin.DIRECT_NORMAL_GOAL : goalOrigin;
        commandDecisionId = normalize(commandDecisionId);
        parentWorkItemId = normalize(parentWorkItemId);
        if (expectedFocusVersion < 0) {
            throw new IllegalArgumentException("expectedFocusVersion must not be negative");
        }
    }

    public static SubmitWorkInputCommand direct(String clientInputId,
                                                String conversationId,
                                                String goal,
                                                long expectedFocusVersion) {
        return new SubmitWorkInputCommand(
                clientInputId, conversationId, goal, goal, GoalOrigin.DIRECT_NORMAL_GOAL,
                "", "", null, expectedFocusVersion
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
