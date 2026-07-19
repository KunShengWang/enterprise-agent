package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.WorkRelationType;

public record CreatePersistedInputWorkItemCommand(
        String inputId,
        String goalText,
        GoalOrigin goalOrigin,
        String commandDecisionId,
        String parentWorkItemId,
        WorkRelationType relationType,
        long expectedFocusVersion
) {
    public CreatePersistedInputWorkItemCommand {
        if (inputId == null || inputId.isBlank() || goalText == null || goalText.isBlank()) {
            throw new IllegalArgumentException("inputId and goalText are required");
        }
        inputId = inputId.trim();
        goalText = goalText.trim();
        goalOrigin = goalOrigin == null ? GoalOrigin.DIRECT_NORMAL_GOAL : goalOrigin;
        commandDecisionId = commandDecisionId == null ? "" : commandDecisionId.trim();
        parentWorkItemId = parentWorkItemId == null ? "" : parentWorkItemId.trim();
        if (expectedFocusVersion < 0) throw new IllegalArgumentException("expectedFocusVersion must not be negative");
    }
}

