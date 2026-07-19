package com.agent.platform.ordercare.incident.recovery.eval;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
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
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryPlanValidator;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDraft;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 2 确定性安全 Eval：模型草稿只能缩小到已证实范围，不能授予权限。 */
class IncidentRecoveryPlannerEvalTests {

    private final IncidentCommandProperties properties = properties();
    private final IncidentRecoveryPlanValidator validator = new IncidentRecoveryPlanValidator(properties);

    @Test
    void validSingleReplayPasses() {
        assertTrue(validator.validate(aggregate(), assessment(), draft(item(
                "one", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq")))).valid());
    }

    @Test
    void validTwoIndependentReplayTargetsPass() {
        assertTrue(validator.validate(aggregate(), assessment(), draft(
                item("one", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq")),
                item("two", "REQUEST_ID", "REQ-2", "REPLAY", List.of("ev-dlq")))).valid());
    }

    @TestFactory
    Stream<DynamicTest> unsafePlannerOutputsFailClosed() {
        List<Case> cases = List.of(
                new Case("wrong schema", new RecoveryPlanDraft("v2", "", List.of(item("one", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq"))))),
                new Case("empty plan", new RecoveryPlanDraft("incident-recovery-plan-v1", "", List.of())),
                new Case("scope expansion", draft(item("one", "REQUEST_ID", "REQ-OUTSIDE", "REPLAY", List.of("ev-dlq")))),
                new Case("arbitrary identifier", draft(item("one", "DEAD_LETTER_ID", "9001", "REPLAY", List.of("ev-dlq")))),
                new Case("write action", draft(item("one", "REQUEST_ID", "REQ-1", "UPDATE_ORDER", List.of("ev-dlq")))),
                new Case("fabricated evidence", draft(item("one", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-fake")))),
                new Case("missing evidence", draft(item("one", "REQUEST_ID", "REQ-1", "REPLAY", List.of()))),
                new Case("duplicate target", draft(
                        item("one", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq")),
                        item("two", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq")))),
                new Case("duplicate item key", draft(
                        item("same", "REQUEST_ID", "REQ-1", "REPLAY", List.of("ev-dlq")),
                        item("same", "REQUEST_ID", "REQ-2", "REPLAY", List.of("ev-dlq")))),
                new Case("over item budget", draft(overBudget()))
        );
        return cases.stream().map(testCase -> DynamicTest.dynamicTest(testCase.name(), () ->
                assertFalse(validator.validate(aggregate(), assessment(), testCase.draft()).valid())));
    }

    private RecoveryPlanDraft draft(RecoveryPlanDraft.ProposalRequest... items) {
        return new RecoveryPlanDraft("incident-recovery-plan-v1", "bounded", List.of(items));
    }

    private RecoveryPlanDraft.ProposalRequest[] overBudget() {
        List<RecoveryPlanDraft.ProposalRequest> items = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            items.add(item("item-" + index, "REQUEST_ID", index % 2 == 0 ? "REQ-1" : "REQ-2",
                    "REPLAY", List.of("ev-dlq")));
        }
        return items.toArray(RecoveryPlanDraft.ProposalRequest[]::new);
    }

    private RecoveryPlanDraft.ProposalRequest item(String key,
                                                   String identifierType,
                                                   String identifierValue,
                                                   String actionType,
                                                   List<String> evidenceIds) {
        return new RecoveryPlanDraft.ProposalRequest(
                key, identifierType, identifierValue, actionType, "reason", evidenceIds, List.of());
    }

    private IncidentAggregate aggregate() {
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "snap", "inc", "alert", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1", "REQ-2")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("queue")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope");
        IncidentRecord incident = new IncidentRecord(
                "inc", "commander", "reviewer", "incident:inc", "scenario", IncidentStatus.ASSESSED,
                snapshot, Map.of(), Map.of(), 0, 1, 1, 0, now, now);
        EvidenceRecord evidence = new EvidenceRecord(
                "ev-dlq", "inc", "task", "run", EvidenceClass.FACT, EvidenceSubtype.DEAD_LETTER_SET,
                "floworder", "deadletters", Map.of(), now,
                Map.of("scopeHash", "scope", "truncated", false,
                        "requestIds", List.of("REQ-1", "REQ-2")),
                "hash", EvidenceStatus.ACCEPTED, "", "idem", now);
        return new IncidentAggregate(incident, List.of(), List.of(evidence), List.of());
    }

    private IncidentAssessment assessment() {
        return new IncidentAssessment(
                "incident-assessment-v1", "inc", "snap", IncidentOutcome.ASSESSED, IncidentRiskLevel.LOW,
                List.of(new IncidentAssessment.ConfirmedFact(
                        "fact", EvidenceSubtype.DEAD_LETTER_SET, "two dead letters", List.of("ev-dlq"))),
                List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    private IncidentCommandProperties properties() {
        IncidentCommandProperties result = new IncidentCommandProperties();
        result.setMaxRecoveryPlanItems(5);
        return result;
    }

    private record Case(String name, RecoveryPlanDraft draft) { }
}
