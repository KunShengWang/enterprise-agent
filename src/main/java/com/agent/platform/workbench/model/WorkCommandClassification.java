package com.agent.platform.workbench.model;

public record WorkCommandClassification(
        WorkCommandType commandType,
        double modelConfidence,
        String reason,
        String targetWorkItemId,
        String derivedGoalText
) {
    public WorkCommandClassification {
        if (commandType == null) {
            throw new IllegalArgumentException("commandType must not be null");
        }
        modelConfidence = Math.max(0, Math.min(1, modelConfidence));
        reason = reason == null ? "" : reason.trim();
        targetWorkItemId = targetWorkItemId == null ? "" : targetWorkItemId.trim();
        derivedGoalText = derivedGoalText == null ? "" : derivedGoalText.trim();
        if (commandType == WorkCommandType.START_NEW_WORK && derivedGoalText.isBlank()) {
            throw new IllegalArgumentException("START_NEW_WORK requires derivedGoalText");
        }
    }
}
