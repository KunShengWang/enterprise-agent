package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.WorkCommandType;

public record CommandClassificationRequest(
        AgentConversationTurn input,
        String focusedWorkItemId,
        String focusedWorkSummary,
        ClassifierType classifierType,
        WorkCommandType explicitCommand,
        String explicitGoalText
) {
    public CommandClassificationRequest {
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        focusedWorkItemId = focusedWorkItemId == null ? "" : focusedWorkItemId.trim();
        focusedWorkSummary = focusedWorkSummary == null ? "" : focusedWorkSummary.trim();
        explicitGoalText = explicitGoalText == null ? "" : explicitGoalText.trim();
        classifierType = classifierType == null ? ClassifierType.MODEL : classifierType;
        if (classifierType != ClassifierType.MODEL && explicitCommand == null) {
            throw new IllegalArgumentException("deterministic classification requires explicitCommand");
        }
        if (explicitCommand == WorkCommandType.START_NEW_WORK && explicitGoalText.isBlank()) {
            throw new IllegalArgumentException("deterministic START_NEW_WORK requires explicitGoalText");
        }
    }
}
