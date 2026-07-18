package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.IncidentAssessment;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentRiskLevel;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class IncidentAssessmentAssembler {

    private static final List<String> FORBIDDEN_ACTIONS = List.of(
            "execute", "replay", "update", "delete", "approve", "force",
            "执行", "重放", "更新", "删除", "审批", "强制");

    public IncidentAssessment assemble(IncidentSnapshot snapshot,
                                       List<EvidenceRecord> evidence,
                                       List<EvidenceConflict> conflicts,
                                       List<EvidenceGap> gaps,
                                       ReviewerAssessmentDraft draft) {
        if (snapshot == null || draft == null || !"reviewer-assessment-v1".equals(draft.schemaVersion())) {
            throw new IncidentAssessmentValidationException(List.of("snapshot or reviewer schema is invalid"));
        }
        Map<String, EvidenceRecord> evidenceById = new HashMap<>();
        for (EvidenceRecord item : evidence == null ? List.<EvidenceRecord>of() : evidence) {
            if (snapshot.incidentId().equals(item.incidentId())) {
                evidenceById.put(item.evidenceId(), item);
            }
        }
        Map<String, EvidenceConflict> conflictById = new HashMap<>();
        for (EvidenceConflict conflict : conflicts == null ? List.<EvidenceConflict>of() : conflicts) {
            conflictById.put(conflict.conflictId(), conflict);
        }
        List<String> errors = new ArrayList<>();
        List<IncidentAssessment.ConfirmedFact> confirmedFacts = new ArrayList<>();
        for (ReviewerAssessmentDraft.ConfirmedFactDraft fact : draft.confirmedFacts()) {
            if (fact.statement().isBlank() || fact.evidenceIds().isEmpty()) {
                errors.add("confirmed fact must contain a statement and evidenceIds");
                continue;
            }
            List<EvidenceRecord> references = validEvidence(fact.evidenceIds(), evidenceById, errors, "confirmedFact");
            if (references.stream().anyMatch(item -> item.evidenceClass() != EvidenceClass.FACT
                    || item.status() != EvidenceStatus.ACCEPTED
                    || item.evidenceSubtype() != fact.evidenceSubtype())) {
                errors.add("confirmed fact references non-accepted FACT or a different subtype");
                continue;
            }
            confirmedFacts.add(new IncidentAssessment.ConfirmedFact(
                    UUID.randomUUID().toString(), fact.evidenceSubtype(), fact.statement(),
                    normalized(fact.evidenceIds())));
        }

        List<IncidentAssessment.RootCauseCandidate> rootCauses = new ArrayList<>();
        for (ReviewerAssessmentDraft.RootCauseDraft root : draft.rootCauseCandidates()) {
            List<EvidenceRecord> evidenceReferences = validEvidence(
                    root.supportingEvidenceIds(), evidenceById, errors, "rootCause");
            validateConflicts(root.relatedConflictIds(), conflictById, errors, "rootCause");
            if (root.hypothesis().isBlank()
                    || (evidenceReferences.isEmpty() && root.relatedConflictIds().isEmpty())
                    || evidenceReferences.stream().anyMatch(item -> item.status() == EvidenceStatus.REJECTED)) {
                errors.add("root cause must have a hypothesis and valid evidence or conflict reference");
                continue;
            }
            rootCauses.add(new IncidentAssessment.RootCauseCandidate(
                    UUID.randomUUID().toString(), root.hypothesis(),
                    normalized(root.supportingEvidenceIds()), normalized(root.relatedConflictIds())));
        }

        List<IncidentAssessment.IncidentRecommendation> recommendations = new ArrayList<>();
        for (ReviewerAssessmentDraft.RecommendationDraft recommendation : draft.recommendations()) {
            List<EvidenceRecord> evidenceReferences = validEvidence(
                    recommendation.evidenceIds(), evidenceById, errors, "recommendation");
            validateConflicts(recommendation.conflictIds(), conflictById, errors, "recommendation");
            String normalizedAction = recommendation.action().toLowerCase(Locale.ROOT);
            if (recommendation.action().isBlank()
                    || (evidenceReferences.isEmpty() && recommendation.conflictIds().isEmpty())
                    || FORBIDDEN_ACTIONS.stream().anyMatch(normalizedAction::contains)) {
                errors.add("recommendation must be referenced and read-only in Phase 1");
                continue;
            }
            recommendations.add(new IncidentAssessment.IncidentRecommendation(
                    UUID.randomUUID().toString(), recommendation.action(),
                    normalized(recommendation.evidenceIds()), normalized(recommendation.conflictIds())));
        }

        Set<String> acknowledged = new HashSet<>(draft.acknowledgedConflictIds());
        conflictById.values().stream()
                .filter(conflict -> conflict.severity() == ConflictSeverity.HIGH)
                .filter(conflict -> "OPEN".equals(conflict.status()))
                .filter(conflict -> !acknowledged.contains(conflict.conflictId()))
                .forEach(conflict -> errors.add("Reviewer omitted open HIGH conflict " + conflict.conflictId()));
        if (!errors.isEmpty()) {
            throw new IncidentAssessmentValidationException(errors);
        }

        List<IncidentAssessment.AssessmentConflict> assessmentConflicts = conflictById.values().stream()
                .sorted(java.util.Comparator.comparing(EvidenceConflict::conflictId))
                .map(conflict -> new IncidentAssessment.AssessmentConflict(
                        conflict.conflictId(), conflict.conflictType(), conflict.severity(), conflict.metricKey(),
                        conflict.relatedEvidenceIds(), conflict.status()))
                .toList();
        List<EvidenceGap> safeGaps = gaps == null ? List.of() : List.copyOf(gaps);
        boolean highOpen = conflictById.values().stream().anyMatch(conflict ->
                conflict.severity() == ConflictSeverity.HIGH && "OPEN".equals(conflict.status()));
        IncidentOutcome outcome = highOpen
                ? IncidentOutcome.MANUAL_REVIEW
                : safeGaps.isEmpty() ? IncidentOutcome.ASSESSED : IncidentOutcome.PARTIAL;
        IncidentRiskLevel risk = highOpen
                ? IncidentRiskLevel.HIGH
                : assessmentConflicts.isEmpty() && safeGaps.isEmpty()
                ? IncidentRiskLevel.LOW
                : IncidentRiskLevel.MEDIUM;
        return new IncidentAssessment(
                "incident-assessment-v1", snapshot.incidentId(), snapshot.snapshotId(), outcome, risk,
                confirmedFacts, assessmentConflicts, rootCauses, recommendations, safeGaps, Instant.now());
    }

    private List<EvidenceRecord> validEvidence(List<String> ids,
                                               Map<String, EvidenceRecord> evidenceById,
                                               List<String> errors,
                                               String owner) {
        List<String> normalized = normalized(ids);
        if (normalized.size() != (ids == null ? 0 : ids.size())) {
            errors.add(owner + " contains duplicate or blank evidence reference");
        }
        List<EvidenceRecord> references = new ArrayList<>();
        for (String id : normalized) {
            EvidenceRecord item = evidenceById.get(id);
            if (item == null) {
                errors.add(owner + " references unknown evidence " + id);
            }
            else {
                references.add(item);
            }
        }
        return references;
    }

    private void validateConflicts(List<String> ids,
                                   Map<String, EvidenceConflict> conflictById,
                                   List<String> errors,
                                   String owner) {
        List<String> normalized = normalized(ids);
        if (normalized.size() != (ids == null ? 0 : ids.size())) {
            errors.add(owner + " contains duplicate or blank conflict reference");
        }
        normalized.stream().filter(id -> !conflictById.containsKey(id))
                .forEach(id -> errors.add(owner + " references unknown conflict " + id));
    }

    private List<String> normalized(List<String> ids) {
        return ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().sorted().toList();
    }
}
