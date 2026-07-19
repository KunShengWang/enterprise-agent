package com.agent.platform.workbench.model;

public record NormalGoalEnvelope(
        String sourceInputId,
        String goalText,
        GoalOrigin goalOrigin,
        String commandDecisionId,
        String parentWorkItemId,
        WorkRelationType relationType
) {

    public NormalGoalEnvelope {
        sourceInputId = requireText(sourceInputId, "sourceInputId");
        goalText = requireText(goalText, "goalText");
        goalOrigin = goalOrigin == null ? GoalOrigin.DIRECT_NORMAL_GOAL : goalOrigin;
        commandDecisionId = normalize(commandDecisionId);
        parentWorkItemId = normalize(parentWorkItemId);
        if (goalOrigin == GoalOrigin.DERIVED_FROM_START_NEW_WORK && commandDecisionId.isBlank()) {
            throw new IllegalArgumentException("derived goal requires commandDecisionId");
        }
        if (goalOrigin == GoalOrigin.DIRECT_NORMAL_GOAL && !commandDecisionId.isBlank()) {
            throw new IllegalArgumentException("direct goal must not carry commandDecisionId");
        }
        if (parentWorkItemId.isBlank() != (relationType == null)) {
            throw new IllegalArgumentException("parentWorkItemId and relationType must be provided together");
        }
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
