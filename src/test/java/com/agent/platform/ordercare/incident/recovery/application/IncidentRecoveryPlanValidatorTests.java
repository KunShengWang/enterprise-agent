package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentAssessment;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentRiskLevel;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentRecoveryPlanValidatorTests {

    private final IncidentCommandProperties properties = new IncidentCommandProperties();
    private final IncidentRecoveryPlanValidator validator = new IncidentRecoveryPlanValidator(properties);

    @Test
    void acceptsBoundedRequestBackedByAssessmentDeadLetterFact() {
        var result = validator.validate(aggregate(false), assessment(), draft("REQ-1", List.of("ev-dlq")));

        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void rejectsTargetOutsideSnapshot() {
        var result = validator.validate(aggregate(false), assessment(), draft("REQ-OTHER", List.of("ev-dlq")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("outside immutable snapshot")));
    }

    @Test
    void rejectsTruncatedDeadLetterFact() {
        var result = validator.validate(aggregate(true), assessment(), draft("REQ-1", List.of("ev-dlq")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("DEAD_LETTER_SET")));
    }

    @Test
    void rejectsEvidenceThatAuthoritativeAssessmentDidNotReference() {
        var result = validator.validate(aggregate(false), assessment(), draft("REQ-1", List.of("ev-order")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("authoritative assessment")));
    }

    @Test
    void rejectsEvidenceGapEvenWhenModelProposesValidTarget() {
        IncidentAssessment base = assessment();
        IncidentAssessment withGap = new IncidentAssessment(
                base.schemaVersion(), base.incidentId(), base.snapshotId(), base.outcome(), base.riskLevel(),
                base.confirmedFacts(), base.conflicts(), base.rootCauseCandidates(), base.recommendations(),
                List.of(new EvidenceGap("BROKER_TIMEOUT", "rabbitmq-management", "broker unavailable")), Instant.now());

        var result = validator.validate(aggregate(false), withGap, draft("REQ-1", List.of("ev-dlq")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("evidence gaps")));
    }

    @Test
    void rejectsOpenHighConflict() {
        IncidentAssessment base = assessment();
        IncidentAssessment conflicted = new IncidentAssessment(
                base.schemaVersion(), base.incidentId(), base.snapshotId(), IncidentOutcome.ASSESSED,
                IncidentRiskLevel.MEDIUM, base.confirmedFacts(),
                List.of(new IncidentAssessment.AssessmentConflict(
                        "conflict-1", com.agent.platform.ordercare.incident.model.EvidenceConflictType.COUNT_MISMATCH,
                        ConflictSeverity.HIGH, "orders-vs-deducts", List.of("ev-dlq"), "OPEN")),
                base.rootCauseCandidates(), base.recommendations(), List.of(), Instant.now());

        var result = validator.validate(aggregate(false), conflicted, draft("REQ-1", List.of("ev-dlq")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("OPEN HIGH")));
    }

    @Test
    void rejectsMoreThanConfiguredMaximum() {
        properties.setMaxRecoveryPlanItems(2);
        List<RecoveryPlanDraft.ProposalRequest> requests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            requests.add(new RecoveryPlanDraft.ProposalRequest(
                    "item-" + i, "REQUEST_ID", "REQ-1", "REPLAY", "reason",
                    List.of("ev-dlq"), List.of()));
        }

        var result = validator.validate(
                aggregate(false), assessment(),
                new RecoveryPlanDraft("incident-recovery-plan-v1", "summary", requests));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("1-2 items")));
    }

    private RecoveryPlanDraft draft(String requestId, List<String> evidenceIds) {
        return new RecoveryPlanDraft(
                "incident-recovery-plan-v1", "safe replay candidate",
                List.of(new RecoveryPlanDraft.ProposalRequest(
                        "replay-1", "REQUEST_ID", requestId, "REPLAY",
                        "dead letter has not converged", evidenceIds, List.of())));
    }

    private IncidentAssessment assessment() {
        return new IncidentAssessment(
                "incident-assessment-v1", "inc-1", "snap-1", IncidentOutcome.ASSESSED,
                IncidentRiskLevel.LOW,
                List.of(new IncidentAssessment.ConfirmedFact(
                        "fact-1", EvidenceSubtype.DEAD_LETTER_SET,
                        "REQ-1 has a persisted stock release dead letter", List.of("ev-dlq"))),
                List.of(), List.of(),
                List.of(new IncidentAssessment.IncidentRecommendation(
                        "rec-1", "enter controlled proposal flow", List.of("ev-dlq"), List.of())),
                List.of(), Instant.now());
    }

    private IncidentAggregate aggregate(boolean truncated) {
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "snap-1", "inc-1", "alert-1", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("queue-1")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-1");
        IncidentRecord incident = new IncidentRecord(
                "inc-1", "run-c", "run-r", "incident:inc-1", "scenario",
                IncidentStatus.ASSESSED, snapshot, Map.of(), Map.of(), 0, 1, 1, 0, now, now);
        EvidenceRecord deadLetter = new EvidenceRecord(
                "ev-dlq", "inc-1", "task-mq", "run-mq", EvidenceClass.FACT,
                EvidenceSubtype.DEAD_LETTER_SET, "floworder", "deadletters", Map.of(), now,
                Map.of("scopeHash", "scope-1", "truncated", truncated, "requestIds", List.of("REQ-1")),
                "hash-dlq", EvidenceStatus.ACCEPTED, "", "idem-dlq", now);
        EvidenceRecord order = new EvidenceRecord(
                "ev-order", "inc-1", "task-order", "run-order", EvidenceClass.FACT,
                EvidenceSubtype.ORDER_STATUS_SET, "floworder", "orders", Map.of(), now,
                Map.of("scopeHash", "scope-1", "requestIds", List.of("REQ-1")),
                "hash-order", EvidenceStatus.ACCEPTED, "", "idem-order", now);
        return new IncidentAggregate(incident, List.of(), List.of(deadLetter, order), List.of());
    }
}
