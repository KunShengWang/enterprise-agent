package com.agent.platform.ordercare.incident.eval;

import com.agent.platform.ordercare.incident.application.DefaultIncidentMqFactsReader;
import com.agent.platform.ordercare.incident.application.DelegationPlanValidator;
import com.agent.platform.ordercare.incident.application.EvidenceConsistencyChecker;
import com.agent.platform.ordercare.incident.application.IncidentAssessmentAssembler;
import com.agent.platform.ordercare.incident.application.IncidentAssessmentValidationException;
import com.agent.platform.ordercare.incident.client.FlowOrderIncidentClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationClient;
import com.agent.platform.ordercare.incident.client.RabbitMqObservationException;
import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceConflictType;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentDeadLetterFacts;
import com.agent.platform.ordercare.incident.model.IncidentFactEnvelope;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 1 简历完成线：10 条固定、无模型随机性的核心 Eval。 */
class IncidentCommandCoreEvalTests {

    private final DelegationPlanValidator planValidator = new DelegationPlanValidator();
    private final EvidenceConsistencyChecker checker = new EvidenceConsistencyChecker();
    private final IncidentAssessmentAssembler assembler = new IncidentAssessmentAssembler();

    @Test
    void eval01_acceptsCompleteReadOnlyDomainSpecialistPlan() {
        DelegationPlan plan = plan(List.of(
                task("order", IncidentAgentRole.ORDER_ANALYST, "查询订单事实", EvidenceSubtype.ORDER_STATUS_SET),
                task("inventory", IncidentAgentRole.INVENTORY_ANALYST, "查询库存事实", EvidenceSubtype.INVENTORY_DEDUCT_SET),
                task("mq", IncidentAgentRole.MQ_ANALYST, "查询死信事实", EvidenceSubtype.DEAD_LETTER_SET)));
        assertTrue(planValidator.validate(plan, snapshot()).valid());
    }

    @Test
    void eval02_rejectsRolesOutsideCompleteDomainCoverage() {
        DelegationPlan plan = plan(List.of(
                task("order", IncidentAgentRole.ORDER_ANALYST, "查询订单事实", EvidenceSubtype.ORDER_STATUS_SET),
                task("inventory", IncidentAgentRole.INVENTORY_ANALYST, "查询库存事实", EvidenceSubtype.INVENTORY_DEDUCT_SET),
                task("mq", IncidentAgentRole.MQ_ANALYST, "查询死信事实", EvidenceSubtype.DEAD_LETTER_SET),
                task("sop", IncidentAgentRole.SOP_ANALYST, "查询处置规范", EvidenceSubtype.SOP_GUIDANCE)));
        assertFalse(planValidator.validate(plan, snapshot()).valid());
    }

    @Test
    void eval03_rejectsWriteOrRecoveryIntent() {
        DelegationPlan plan = plan(List.of(
                task("mq", IncidentAgentRole.MQ_ANALYST, "重放死信并恢复订单", EvidenceSubtype.DEAD_LETTER_SET)));
        assertTrue(planValidator.validate(plan, snapshot()).errors().stream()
                .anyMatch(error -> error.contains("write or recovery")));
    }

    @Test
    void eval04_rejectsEvidenceSubtypeOutsideRoleBoundary() {
        DelegationPlan plan = plan(List.of(
                task("order", IncidentAgentRole.ORDER_ANALYST, "查询订单事实", EvidenceSubtype.DEAD_LETTER_SET)));
        assertFalse(planValidator.validate(plan, snapshot()).valid());
    }

    @Test
    void eval05_detectsTheExplicitOneHundredVersusNinetyThreeCountMismatch() {
        Instant observed = Instant.now();
        var result = checker.check(snapshot(observed), List.of(
                evidence("orders", EvidenceSubtype.ORDER_STATUS_SET, observed, Map.of(
                        "scopeHash", "scope-1", "truncated", false,
                        "terminalDistinctRequestIdCount", 100, "terminalRequestIds", ids(100))),
                evidence("inventory", EvidenceSubtype.INVENTORY_DEDUCT_SET, observed, Map.of(
                        "scopeHash", "scope-1", "truncated", false,
                        "unreleasedDistinctRequestIdCount", 93, "unreleasedRequestIds", ids(93), "items", List.of()))
        ), Set.of());
        assertTrue(result.conflicts().stream().anyMatch(conflict ->
                conflict.conflictType() == EvidenceConflictType.COUNT_MISMATCH
                        && "terminal-orders-vs-unreleased-deducts".equals(conflict.metricKey())));
    }

    @Test
    void eval06_doesNotCompareRabbitQueueDepthWithBusinessRecordCounts() {
        Instant observed = Instant.now();
        var result = checker.check(snapshot(observed), List.of(
                evidence("dead", EvidenceSubtype.DEAD_LETTER_SET, observed, Map.of(
                        "scopeHash", "scope-1", "truncated", false,
                        "recordCount", 126, "requestIds", ids(100))),
                evidence("queue", EvidenceSubtype.QUEUE_RUNTIME_STATUS, observed, Map.of(
                        "scopeHash", "scope-1", "truncated", false,
                        "messagesReady", 126, "consumerCount", 0))
        ), Set.of());
        assertFalse(result.conflicts().stream().anyMatch(conflict ->
                conflict.metricKey().toLowerCase().contains("queue")));
    }

    @Test
    void eval07_refusesCrossScopeComparison() {
        Instant observed = Instant.now();
        var result = checker.check(snapshot(observed), List.of(
                evidence("orders", EvidenceSubtype.ORDER_STATUS_SET, observed, Map.of(
                        "scopeHash", "scope-1", "truncated", false,
                        "terminalDistinctRequestIdCount", 3, "terminalRequestIds", ids(3))),
                evidence("inventory", EvidenceSubtype.INVENTORY_DEDUCT_SET, observed, Map.of(
                        "scopeHash", "other-scope", "truncated", false,
                        "unreleasedDistinctRequestIdCount", 1, "unreleasedRequestIds", ids(1), "items", List.of()))
        ), Set.of());
        assertFalse(result.notComparable().isEmpty());
        assertFalse(result.conflicts().stream().anyMatch(conflict ->
                conflict.conflictType() == EvidenceConflictType.COUNT_MISMATCH));
    }

    @Test
    void eval08_keepsPersistedDeadLettersWhenRabbitManagementTimesOut() {
        FlowOrderIncidentClient flowOrder = mock(FlowOrderIncidentClient.class);
        RabbitMqObservationClient rabbit = mock(RabbitMqObservationClient.class);
        IncidentFactQuery query = new IncidentFactQuery(
                "inc-1", "snap-1", "scope-1", List.of("REQ-001"), List.of("orders.dlq"), 500);
        IncidentFactEnvelope<IncidentDeadLetterFacts> persisted = new IncidentFactEnvelope<>(
                "floworder-incident-facts-v1", "floworder", "deadletters", "scope-1",
                OffsetDateTime.now(), false, List.of(), new IncidentDeadLetterFacts(
                1, 1, 1, 1, 0, 0, List.of("DED-1"), List.of("REQ-001"),
                List.of(1L), List.of(), List.of()));
        when(flowOrder.queryDeadLetterFacts(query, "trace-1")).thenReturn(persisted);
        when(rabbit.observeQueues(query.queueNames(), "trace-1"))
                .thenThrow(new RabbitMqObservationException("timeout", true, null));
        var result = new DefaultIncidentMqFactsReader(flowOrder, rabbit).read(query, "trace-1");
        assertTrue(result.partial());
        assertEquals(1, result.deadLetterFacts().facts().recordCount());
        assertEquals("BROKER_TIMEOUT", result.evidenceGaps().get(0).code());
    }

    @Test
    void eval09_rejectsReviewerFactsWithUnknownEvidenceReferences() {
        ReviewerAssessmentDraft draft = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1",
                List.of(new ReviewerAssessmentDraft.ConfirmedFactDraft(
                        EvidenceSubtype.ORDER_STATUS_SET, "订单均已终态", List.of("unknown"))),
                List.of(), List.of(), null, List.of());
        assertThrows(IncidentAssessmentValidationException.class,
                () -> assembler.assemble(snapshot(), List.of(), List.of(), List.of(), draft));
    }

    @Test
    void eval10_openHighConflictRemainsManualReviewEvenWhenReviewerAcknowledgesIt() {
        Instant now = Instant.now();
        EvidenceRecord orders = evidence("orders", EvidenceSubtype.ORDER_STATUS_SET, now, Map.of(
                "scopeHash", "scope-1", "truncated", false,
                "terminalDistinctRequestIdCount", 3, "terminalRequestIds", ids(3)));
        EvidenceConflict conflict = new EvidenceConflict(
                "conflict-1", EvidenceConflictType.COUNT_MISMATCH, "explicit-rule",
                ConflictSeverity.HIGH, List.of("orders"), Map.of(), "OPEN");
        ReviewerAssessmentDraft draft = new ReviewerAssessmentDraft(
                "reviewer-assessment-v1",
                List.of(new ReviewerAssessmentDraft.ConfirmedFactDraft(
                        EvidenceSubtype.ORDER_STATUS_SET, "订单事实已确认", List.of("orders"))),
                List.of(), List.of(), null, List.of("conflict-1"));
        assertEquals(IncidentOutcome.MANUAL_REVIEW,
                assembler.assemble(snapshot(), List.of(orders), List.of(conflict), List.of(), draft).outcome());
    }

    private DelegationPlan plan(List<DelegationPlan.DelegatedTask> tasks) {
        return new DelegationPlan("delegation-plan-v1", "inc-1", "只读调查", tasks);
    }

    private DelegationPlan.DelegatedTask task(String key,
                                              IncidentAgentRole role,
                                              String objective,
                                              EvidenceSubtype subtype) {
        return new DelegationPlan.DelegatedTask(key, role, objective, 50, List.of(), List.of(subtype));
    }

    private IncidentSnapshot snapshot() {
        return snapshot(Instant.now());
    }

    private IncidentSnapshot snapshot(Instant now) {
        return new IncidentSnapshot(
                "snap-1", "inc-1", "alert-1", "ORDER_STATE_INCONSISTENCY", "tenant",
                new IncidentSnapshot.IncidentOrderScope(ids(100)),
                new IncidentSnapshot.IncidentBusinessScope(List.of("floworder.order.state.dlq")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now.minusSeconds(5), now, now.plusSeconds(120), "scope-1");
    }

    private EvidenceRecord evidence(String id,
                                    EvidenceSubtype subtype,
                                    Instant observedAt,
                                    Map<String, Object> facts) {
        return new EvidenceRecord(
                id, "inc-1", "task-1", "run-1", EvidenceClass.FACT, subtype,
                "floworder", "source:" + id, Map.of(), observedAt,
                new LinkedHashMap<>(facts), "hash-" + id, EvidenceStatus.ACCEPTED,
                "", "idem-" + id, observedAt);
    }

    private List<String> ids(int count) {
        List<String> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            values.add("REQ-%03d".formatted(index));
        }
        return List.copyOf(values);
    }
}
