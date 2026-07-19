package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceConflictType;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentAssessmentAssemblerTests {

    private final IncidentAssessmentAssembler assembler = new IncidentAssessmentAssembler();

    @Test
    void rejectsUnknownEvidenceAndOmittedOpenHighConflict() {
        ReviewerAssessmentDraft draft = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1",
                List.of(new ReviewerAssessmentDraft.ConfirmedFactDraft(
                        EvidenceSubtype.ORDER_STATUS_SET, "100 orders", List.of("missing-evidence"))),
                List.of(), List.of(), null, List.of());

        assertThrows(IncidentAssessmentValidationException.class, () -> assembler.assemble(
                snapshot(), List.of(evidence()), List.of(conflict()), List.of(), draft));
    }

    @Test
    void keepsJavaConflictAuthoritativeEvenWhenReviewerAcknowledgesIt() {
        ReviewerAssessmentDraft draft = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1",
                List.of(new ReviewerAssessmentDraft.ConfirmedFactDraft(
                        EvidenceSubtype.ORDER_STATUS_SET, "订单事实已确认", List.of("ev-order"))),
                List.of(new ReviewerAssessmentDraft.RootCauseDraft(
                        "订单与扣减集合不一致", List.of("ev-order"), List.of("conflict-1"))),
                List.of(new ReviewerAssessmentDraft.RecommendationDraft(
                        "建议值班人员核对受影响 requestId 并进入后续受控 Proposal 流程",
                        List.of("ev-order"), List.of("conflict-1"))),
                null,
                List.of("conflict-1"));

        var assessment = assembler.assemble(
                snapshot(), List.of(evidence()), List.of(conflict()), List.of(), draft);

        assertEquals(IncidentOutcome.MANUAL_REVIEW, assessment.outcome());
        assertEquals("OPEN", assessment.conflicts().get(0).status());
    }

    @Test
    void rejectsEmptyAssessmentWhenAcceptedFactsExist() {
        ReviewerAssessmentDraft empty = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1", List.of(), List.of(), List.of(), null, List.of());

        IncidentAssessmentValidationException error = assertThrows(
                IncidentAssessmentValidationException.class,
                () -> assembler.assemble(snapshot(), List.of(evidence()), List.of(), List.of(), empty));

        assertEquals(true, error.getMessage().contains("at least one accepted FACT"));
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.now();
        return new IncidentSnapshot(
                "snap-1", "inc-1", "alert", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of()),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope");
    }

    private EvidenceRecord evidence() {
        Instant now = Instant.now();
        return new EvidenceRecord(
                "ev-order", "inc-1", "task-1", "run-1", EvidenceClass.FACT,
                EvidenceSubtype.ORDER_STATUS_SET, "floworder", "order-facts", Map.of(), now,
                Map.of("scopeHash", "scope", "terminalDistinctRequestIdCount", 1),
                "hash", EvidenceStatus.ACCEPTED, "", "idem", now);
    }

    private EvidenceConflict conflict() {
        return new EvidenceConflict(
                "conflict-1", EvidenceConflictType.COUNT_MISMATCH, "orders-vs-deducts",
                ConflictSeverity.HIGH, List.of("ev-order"), Map.of("left", 1, "right", 0), "OPEN");
    }
}
