package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentAssessment;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentRiskLevel;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDraft;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IncidentRecoveryPlanValidator {

    private static final String SCHEMA_VERSION = "incident-recovery-plan-v1";
    private static final Set<String> ALLOWED_IDENTIFIER_TYPES = Set.of("REQUEST_ID");
    private static final Set<String> ALLOWED_ACTION_TYPES = Set.of("REPLAY");

    private final IncidentCommandProperties properties;

    public IncidentRecoveryPlanValidator(IncidentCommandProperties properties) {
        this.properties = properties;
    }

    public RecoveryPlanValidationResult validate(IncidentAggregate aggregate,
                                                 IncidentAssessment assessment,
                                                 RecoveryPlanDraft draft) {
        List<String> errors = new ArrayList<>(validateEligibility(aggregate, assessment).errors());
        if (aggregate == null || aggregate.incident() == null || assessment == null) {
            return RecoveryPlanValidationResult.rejected(errors);
        }
        if (draft == null || !SCHEMA_VERSION.equals(draft.schemaVersion())) {
            errors.add("schemaVersion must be " + SCHEMA_VERSION);
            return RecoveryPlanValidationResult.rejected(errors);
        }
        if (draft.proposalRequests().isEmpty()
                || draft.proposalRequests().size() > properties.getMaxRecoveryPlanItems()) {
            errors.add("proposalRequests must contain 1-" + properties.getMaxRecoveryPlanItems() + " items");
        }

        Map<String, EvidenceRecord> evidenceById = aggregate.evidence().stream()
                .collect(Collectors.toMap(EvidenceRecord::evidenceId, Function.identity(), (left, right) -> left));
        Set<String> assessmentEvidenceIds = assessmentEvidenceIds(assessment);
        Set<String> assessmentConflictIds = assessment.conflicts().stream()
                .map(IncidentAssessment.AssessmentConflict::conflictId)
                .collect(Collectors.toSet());
        Set<String> scopeRequestIds = new HashSet<>(aggregate.incident().snapshot().orderScope().requestIds());
        Set<String> itemKeys = new HashSet<>();
        Set<String> targets = new HashSet<>();

        for (int index = 0; index < draft.proposalRequests().size(); index++) {
            RecoveryPlanDraft.ProposalRequest item = draft.proposalRequests().get(index);
            String prefix = "proposalRequests[" + index + "]: ";
            if (item == null) {
                errors.add(prefix + "item is null");
                continue;
            }
            if (!hasText(item.clientItemKey()) || !itemKeys.add(item.clientItemKey())) {
                errors.add(prefix + "clientItemKey is blank or duplicated");
            }
            if (!ALLOWED_IDENTIFIER_TYPES.contains(normalized(item.identifierType()))) {
                errors.add(prefix + "identifierType must be REQUEST_ID");
            }
            if (!ALLOWED_ACTION_TYPES.contains(normalized(item.actionType()))) {
                errors.add(prefix + "actionType must be REPLAY");
            }
            if (!hasText(item.identifierValue()) || !scopeRequestIds.contains(item.identifierValue().trim())) {
                errors.add(prefix + "identifierValue is outside immutable snapshot scope");
            }
            String targetKey = normalized(item.identifierType()) + ":" + safe(item.identifierValue())
                    + ":" + normalized(item.actionType());
            if (!targets.add(targetKey)) {
                errors.add(prefix + "recovery target is duplicated");
            }
            if (!hasText(item.suggestedReason())) {
                errors.add(prefix + "suggestedReason must not be blank");
            }
            if (item.evidenceIds().isEmpty()) {
                errors.add(prefix + "at least one evidenceId is required");
            }
            boolean deadLetterProvesTarget = false;
            for (String evidenceId : new LinkedHashSet<>(item.evidenceIds())) {
                EvidenceRecord evidence = evidenceById.get(evidenceId);
                if (evidence == null || evidence.status() != EvidenceStatus.ACCEPTED
                        || evidence.evidenceClass() != EvidenceClass.FACT) {
                    errors.add(prefix + "invalid FACT evidence reference: " + evidenceId);
                    continue;
                }
                if (!assessmentEvidenceIds.contains(evidenceId)) {
                    errors.add(prefix + "evidence is not referenced by authoritative assessment: " + evidenceId);
                }
                if (evidence.evidenceSubtype() == EvidenceSubtype.DEAD_LETTER_SET
                        && provesRequestId(evidence, item.identifierValue(), aggregate.incident().snapshot().scopeHash())) {
                    deadLetterProvesTarget = true;
                }
            }
            if (!deadLetterProvesTarget) {
                errors.add(prefix + "no non-truncated DEAD_LETTER_SET FACT proves this requestId");
            }
            for (String conflictId : new LinkedHashSet<>(item.conflictIds())) {
                if (!assessmentConflictIds.contains(conflictId)) {
                    errors.add(prefix + "invalid conflictId reference: " + conflictId);
                }
            }
        }
        return errors.isEmpty()
                ? RecoveryPlanValidationResult.accepted()
                : RecoveryPlanValidationResult.rejected(errors);
    }

    public RecoveryPlanValidationResult validateEligibility(IncidentAggregate aggregate,
                                                            IncidentAssessment assessment) {
        List<String> errors = new ArrayList<>();
        if (aggregate == null || aggregate.incident() == null || assessment == null) {
            return RecoveryPlanValidationResult.rejected(
                    List.of("incident aggregate or assessment is missing"));
        }
        if (aggregate.incident().status() != IncidentStatus.ASSESSED
                || assessment.outcome() != IncidentOutcome.ASSESSED) {
            errors.add("only an ASSESSED incident may enter recovery planning");
        }
        if (assessment.riskLevel() == IncidentRiskLevel.HIGH) {
            errors.add("HIGH risk assessment must remain manual review");
        }
        boolean openHighConflict = assessment.conflicts().stream().anyMatch(conflict ->
                conflict.severity() == ConflictSeverity.HIGH
                        && !"RESOLVED".equalsIgnoreCase(conflict.status()));
        if (openHighConflict) {
            errors.add("OPEN HIGH conflict forbids recovery planning");
        }
        if (!assessment.evidenceGaps().isEmpty()) {
            errors.add("evidence gaps must be resolved before recovery planning");
        }
        return errors.isEmpty()
                ? RecoveryPlanValidationResult.accepted()
                : RecoveryPlanValidationResult.rejected(errors);
    }

    private Set<String> assessmentEvidenceIds(IncidentAssessment assessment) {
        Set<String> result = new HashSet<>();
        assessment.confirmedFacts().forEach(item -> result.addAll(item.evidenceIds()));
        assessment.rootCauseCandidates().forEach(item -> result.addAll(item.supportingEvidenceIds()));
        assessment.recommendations().forEach(item -> result.addAll(item.evidenceIds()));
        assessment.conflicts().forEach(item -> result.addAll(item.evidenceIds()));
        return result;
    }

    private boolean provesRequestId(EvidenceRecord evidence, String requestId, String scopeHash) {
        if (Boolean.TRUE.equals(evidence.facts().get("truncated"))) {
            return false;
        }
        if (!scopeHash.equals(String.valueOf(evidence.facts().getOrDefault("scopeHash", "")))) {
            return false;
        }
        Object raw = evidence.facts().get("requestIds");
        if (!(raw instanceof Iterable<?> values)) {
            return false;
        }
        for (Object value : values) {
            if (requestId != null && requestId.trim().equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private String normalized(String value) {
        return safe(value).toUpperCase();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
