package com.agent.platform.runtime;

import java.util.List;
import java.util.Map;

/** 只允许 Incident Reviewer 创建的结构化定向输入，不是通用的终态 Run 重开协议。 */
public record AgentFollowUpInput(
        String schemaVersion,
        String followUpType,
        String originalTaskId,
        String conflictId,
        List<String> relatedEvidenceIds,
        String question,
        int additionalToolBudget,
        long additionalTokenBudget,
        Map<String, Object> metadata
) {

    public AgentFollowUpInput {
        schemaVersion = normalize(schemaVersion, "follow-up-task-v1");
        followUpType = normalize(followUpType, "EVIDENCE_CLARIFICATION");
        originalTaskId = normalize(originalTaskId, "");
        conflictId = normalize(conflictId, "");
        relatedEvidenceIds = relatedEvidenceIds == null ? List.of() : relatedEvidenceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        question = normalize(question, "");
        additionalToolBudget = Math.max(0, additionalToolBudget);
        additionalTokenBudget = Math.max(0, additionalTokenBudget);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (!"follow-up-task-v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported follow-up schema: " + schemaVersion);
        }
        if (question.isBlank()) {
            throw new IllegalArgumentException("follow-up question must not be blank");
        }
    }

    public String timelineContent() {
        return """
                [FOLLOW_UP_TASK_V1]
                type=%s
                originalTaskId=%s
                conflictId=%s
                relatedEvidenceIds=%s
                question=%s
                """.formatted(followUpType, originalTaskId, conflictId, relatedEvidenceIds, question).trim();
    }

    public Map<String, Object> timelineMetadata(int ordinal) {
        return Map.of(
                "schemaVersion", schemaVersion,
                "followUpType", followUpType,
                "originalTaskId", originalTaskId,
                "conflictId", conflictId,
                "relatedEvidenceIds", relatedEvidenceIds,
                "followUpOrdinal", ordinal,
                "additionalToolBudget", additionalToolBudget,
                "additionalTokenBudget", additionalTokenBudget
        );
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
