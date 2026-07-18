package com.agent.platform.ordercare.incident.model;

import java.util.List;

/** Reviewer 的非权威候选；最终 outcome、引用和冲突状态由 Java Assembler 决定。 */
public record ReviewerAssessmentDraft(
        String schemaVersion,
        List<ConfirmedFactDraft> confirmedFacts,
        List<RootCauseDraft> rootCauseCandidates,
        List<RecommendationDraft> recommendations,
        ClarificationRequest clarificationRequest,
        List<String> acknowledgedConflictIds
) {
    public ReviewerAssessmentDraft {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        rootCauseCandidates = rootCauseCandidates == null ? List.of() : List.copyOf(rootCauseCandidates);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        acknowledgedConflictIds = acknowledgedConflictIds == null
                ? List.of()
                : acknowledgedConflictIds.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
    }

    public record ConfirmedFactDraft(
            EvidenceSubtype evidenceSubtype,
            String statement,
            List<String> evidenceIds
    ) {
        public ConfirmedFactDraft {
            statement = statement == null ? "" : statement.trim();
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record RootCauseDraft(
            String hypothesis,
            List<String> supportingEvidenceIds,
            List<String> relatedConflictIds
    ) {
        public RootCauseDraft {
            hypothesis = hypothesis == null ? "" : hypothesis.trim();
            supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
            relatedConflictIds = relatedConflictIds == null ? List.of() : List.copyOf(relatedConflictIds);
        }
    }

    public record RecommendationDraft(
            String action,
            List<String> evidenceIds,
            List<String> conflictIds
    ) {
        public RecommendationDraft {
            action = action == null ? "" : action.trim();
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            conflictIds = conflictIds == null ? List.of() : List.copyOf(conflictIds);
        }
    }

    public record ClarificationRequest(
            String taskId,
            String conflictId,
            List<String> relatedEvidenceIds,
            String question
    ) {
        public ClarificationRequest {
            taskId = taskId == null ? "" : taskId.trim();
            conflictId = conflictId == null ? "" : conflictId.trim();
            relatedEvidenceIds = relatedEvidenceIds == null ? List.of() : List.copyOf(relatedEvidenceIds);
            question = question == null ? "" : question.trim();
        }
    }
}
