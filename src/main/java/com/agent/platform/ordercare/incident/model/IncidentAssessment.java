package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.List;

public record IncidentAssessment(
        String schemaVersion,
        String incidentId,
        String snapshotId,
        IncidentOutcome outcome,
        IncidentRiskLevel riskLevel,
        List<ConfirmedFact> confirmedFacts,
        List<AssessmentConflict> conflicts,
        List<RootCauseCandidate> rootCauseCandidates,
        List<IncidentRecommendation> recommendations,
        List<EvidenceGap> evidenceGaps,
        Instant generatedAt
) {
    public IncidentAssessment {
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        rootCauseCandidates = rootCauseCandidates == null ? List.of() : List.copyOf(rootCauseCandidates);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
    }

    public record ConfirmedFact(
            String factId,
            EvidenceSubtype evidenceSubtype,
            String statement,
            List<String> evidenceIds
    ) {}

    public record AssessmentConflict(
            String conflictId,
            EvidenceConflictType conflictType,
            ConflictSeverity severity,
            String metricKey,
            List<String> evidenceIds,
            String status
    ) {}

    public record RootCauseCandidate(
            String candidateId,
            String hypothesis,
            List<String> supportingEvidenceIds,
            List<String> relatedConflictIds
    ) {}

    public record IncidentRecommendation(
            String recommendationId,
            String action,
            List<String> evidenceIds,
            List<String> conflictIds
    ) {}
}
