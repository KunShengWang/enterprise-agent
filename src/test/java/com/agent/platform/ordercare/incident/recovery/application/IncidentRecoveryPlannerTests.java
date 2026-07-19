package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.incident.application.IncidentExecutionProfileFactory;
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
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentStopReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentRecoveryPlannerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IncidentStore incidentStore = Mockito.mock(IncidentStore.class);
    private final TaskEventStore eventStore = Mockito.mock(TaskEventStore.class);
    private final AgentRuntime agentRuntime = Mockito.mock(AgentRuntime.class);
    private final FlowOrderClient flowOrderClient = Mockito.mock(FlowOrderClient.class);
    private final OrderCareProposalBindingStore bindingStore = Mockito.mock(OrderCareProposalBindingStore.class);
    private final ApprovalService approvalService = Mockito.mock(ApprovalService.class);
    private IncidentCommandProperties properties;
    private InMemoryPlanStore planStore;
    private IncidentRecoveryPlanner planner;

    @BeforeEach
    void setUp() {
        properties = new IncidentCommandProperties();
        properties.setEnabled(true);
        properties.setRecoveryPlannerEnabled(true);
        properties.setMaxRecoveryPlanItems(5);
        planStore = new InMemoryPlanStore();
        IncidentAggregate aggregate = aggregate();
        when(incidentStore.findAggregate(anyString(), any(Integer.class))).thenReturn(Optional.of(aggregate));
        when(eventStore.appendEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(approvalService.find(anyString())).thenReturn(Optional.empty());
        when(approvalService.requestApproval(any())).thenReturn(new ApprovalDecision(
                "approval", ApprovalStatus.REQUESTED, "", "waiting", null));
        planner = new IncidentRecoveryPlanner(
                properties, incidentStore, planStore, eventStore,
                new IncidentExecutionProfileFactory(),
                new IncidentRecoveryPlanValidator(properties),
                new RecoveryPlanDigest(objectMapper), agentRuntime, flowOrderClient,
                bindingStore, approvalService, objectMapper);
    }

    @Test
    void validPlannerDraftCreatesIndependentFlowOrderProposalAndApproval() {
        when(agentRuntime.run(any(), any(), any())).thenReturn(runtimeResult("""
                {"schemaVersion":"incident-recovery-plan-v1","summary":"safe candidate","proposalRequests":[
                  {"clientItemKey":"replay-req-1","identifierType":"REQUEST_ID","identifierValue":"REQ-1",
                   "actionType":"REPLAY","suggestedReason":"stock release dead letter remains",
                   "evidenceIds":["ev-dlq"],"conflictIds":[]}
                ]}
                """));
        when(flowOrderClient.createProposal(any(), anyString())).thenReturn(proposal());

        var started = planner.initialize("inc-1", new RecoveryPlanStartRequest("request-1", "plan recovery"));
        var planned = planner.plan(started.planId(), "plan recovery");

        assertEquals(RecoveryPlanStatus.WAITING_APPROVAL, planned.status());
        assertEquals("run-planner", planned.plannerRunId());
        assertEquals(1, planned.items().size());
        assertEquals(RecoveryPlanItemStatus.WAITING_APPROVAL, planned.items().get(0).status());
        assertFalse(planned.items().get(0).approvalId().isBlank());
        verify(flowOrderClient).createProposal(any(), anyString());
        verify(approvalService).requestApproval(any());
    }

    @Test
    void outOfScopePlannerDraftFailsClosedWithoutCreatingProposal() {
        when(agentRuntime.run(any(), any(), any())).thenReturn(runtimeResult("""
                {"schemaVersion":"incident-recovery-plan-v1","summary":"bad","proposalRequests":[
                  {"clientItemKey":"bad","identifierType":"REQUEST_ID","identifierValue":"REQ-OTHER",
                   "actionType":"REPLAY","suggestedReason":"invented target",
                   "evidenceIds":["ev-dlq"],"conflictIds":[]}
                ]}
                """));

        var started = planner.initialize("inc-1", new RecoveryPlanStartRequest("request-2", "bad plan"));
        var planned = planner.plan(started.planId(), "bad plan");

        assertEquals(RecoveryPlanStatus.FAILED, planned.status());
        assertTrue(planned.validationErrors().stream().anyMatch(error -> error.contains("outside immutable snapshot")));
        verify(flowOrderClient, never()).createProposal(any(), anyString());
    }

    @Test
    void sameRequestKeyReturnsSamePlanWithoutCreatingAnotherAggregate() {
        var first = planner.initialize("inc-1", new RecoveryPlanStartRequest("same", "first"));
        var second = planner.initialize("inc-1", new RecoveryPlanStartRequest("same", "second"));

        assertEquals(first.planId(), second.planId());
        assertTrue(first.newlyCreated());
        assertFalse(second.newlyCreated());
        assertEquals(1, planStore.values.size());
    }

    private AgentRuntimeResult runtimeResult(String answer) {
        return new AgentRuntimeResult(
                "run-planner", "session", AgentRunState.COMPLETED, AgentStopReason.COMPLETED,
                answer, "", null, List.of());
    }

    private IncidentAggregate aggregate() {
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(
                "snap-1", "inc-1", "alert", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("queue")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope-1");
        IncidentAssessment assessment = new IncidentAssessment(
                "incident-assessment-v1", "inc-1", "snap-1", IncidentOutcome.ASSESSED,
                IncidentRiskLevel.LOW,
                List.of(new IncidentAssessment.ConfirmedFact(
                        "fact-1", EvidenceSubtype.DEAD_LETTER_SET, "dead letter exists", List.of("ev-dlq"))),
                List.of(), List.of(),
                List.of(new IncidentAssessment.IncidentRecommendation(
                        "rec-1", "controlled proposal", List.of("ev-dlq"), List.of())),
                List.of(), now);
        IncidentRecord incident = new IncidentRecord(
                "inc-1", "run-c", "run-r", "incident:inc-1", "scenario",
                IncidentStatus.ASSESSED, snapshot, Map.of(), objectMapper.convertValue(assessment, Map.class),
                0, 1, 1, 0, now, now);
        EvidenceRecord evidence = new EvidenceRecord(
                "ev-dlq", "inc-1", "task", "run", EvidenceClass.FACT, EvidenceSubtype.DEAD_LETTER_SET,
                "floworder", "deadletters", Map.of(), now,
                Map.of("scopeHash", "scope-1", "truncated", false, "requestIds", List.of("REQ-1")),
                "hash", EvidenceStatus.ACCEPTED, "", "idem", now);
        return new IncidentAggregate(incident, List.of(), List.of(evidence), List.of());
    }

    private OrderCareRecoveryProposal proposal() {
        return new OrderCareRecoveryProposal(
                "floworder-recovery-proposal-v1", "prop-1", 1, "ACTIVE", "action-1",
                "NOT_STARTED", "NOT_CONVERGED", "case-1", "REQUEST_ID", "REQ-1", "REPLAY",
                "DEAD_LETTER", "target", "fingerprint", "effects", "warnings", "preview",
                true, List.of("replay"), List.of("approval required"), "reason",
                "", "", "", "", nowPlus(), now(), now());
    }

    private String nowPlus() { return Instant.now().plusSeconds(300).toString(); }
    private String now() { return Instant.now().toString(); }

    private static final class InMemoryPlanStore implements IncidentRecoveryPlanStore {
        private final List<IncidentRecoveryPlanRecord> values = new ArrayList<>();

        @Override public IncidentRecoveryPlanRecord create(IncidentRecoveryPlanRecord plan) { values.add(plan); return plan; }
        @Override public Optional<IncidentRecoveryPlanRecord> find(String planId) { return values.stream().filter(p -> p.planId().equals(planId)).findFirst(); }
        @Override public Optional<IncidentRecoveryPlanRecord> findByRequestKey(String incidentId, String requestKey) { return values.stream().filter(p -> p.incidentId().equals(incidentId) && p.requestKey().equals(requestKey)).findFirst(); }
        @Override public List<IncidentRecoveryPlanRecord> listByIncident(String incidentId) { return values.stream().filter(p -> p.incidentId().equals(incidentId)).toList(); }
        @Override public IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion) {
            for (int index = 0; index < values.size(); index++) {
                IncidentRecoveryPlanRecord current = values.get(index);
                if (current.planId().equals(next.planId())) {
                    if (current.version() != expectedVersion) throw new IncidentCasConflictException("CAS");
                    values.set(index, next);
                    return next;
                }
            }
            throw new IllegalArgumentException("missing plan");
        }
    }
}
