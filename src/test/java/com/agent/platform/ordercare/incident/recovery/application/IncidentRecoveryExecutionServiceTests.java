package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.RecoveryConvergenceChecker;
import com.agent.platform.ordercare.application.RecoveryOutcomeReconciler;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDecisionRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentRecoveryExecutionServiceTests {

    private final ApprovalService approvalService = Mockito.mock(ApprovalService.class);
    private final FlowOrderClient flowOrderClient = Mockito.mock(FlowOrderClient.class);
    private final RecoveryConvergenceChecker convergenceChecker = Mockito.mock(RecoveryConvergenceChecker.class);
    private final RecoveryOutcomeReconciler outcomeReconciler = Mockito.mock(RecoveryOutcomeReconciler.class);
    private InMemoryPlanStore store;
    private IncidentRecoveryExecutionService service;

    @BeforeEach
    void setUp() {
        IncidentCommandProperties properties = new IncidentCommandProperties();
        properties.setEnabled(true);
        properties.setRecoveryPlannerEnabled(true);
        store = new InMemoryPlanStore(plan());
        service = new IncidentRecoveryExecutionService(
                properties, store, approvalService, flowOrderClient, convergenceChecker, outcomeReconciler);
    }

    @Test
    void approvedItemExecutesOriginalProposalOnceAndBecomesResolved() {
        approve();
        when(flowOrderClient.getProposal(anyString(), anyString())).thenReturn(proposal());
        when(flowOrderClient.executeProposal(any(), anyString())).thenReturn(proposal());
        when(convergenceChecker.await(anyString(), anyString())).thenReturn(new OrderCareConvergenceResult(
                "prop-1", "RESOLVED", 2, "EXECUTED", "SUBMITTED", "RESOLVED",
                true, true, true));

        var first = service.decideAndExecute(
                "plan-1", "item-1", new RecoveryPlanDecisionRequest(true, "reviewer", "checked"));
        var duplicate = service.decideAndExecute(
                "plan-1", "item-1", new RecoveryPlanDecisionRequest(true, "reviewer", "checked"));

        assertEquals(RecoveryPlanOutcome.RESOLVED, first.outcome());
        assertEquals(RecoveryPlanItemStatus.RESOLVED, first.items().get(0).status());
        assertEquals(RecoveryPlanItemStatus.RESOLVED, duplicate.items().get(0).status());
        verify(flowOrderClient, times(1)).executeProposal(any(), anyString());
    }

    @Test
    void rejectedItemNeverCallsFlowOrderExecute() {
        when(approvalService.decide(anyString(), Mockito.eq(false), anyString(), anyString()))
                .thenReturn(new ApprovalDecision(
                        "approval-1", ApprovalStatus.REJECTED, "reviewer", "reject", Instant.now()));

        var result = service.decideAndExecute(
                "plan-1", "item-1", new RecoveryPlanDecisionRequest(false, "reviewer", "reject"));

        assertEquals(RecoveryPlanOutcome.REJECTED, result.outcome());
        assertEquals(RecoveryPlanItemStatus.REJECTED, result.items().get(0).status());
        verify(flowOrderClient, never()).executeProposal(any(), anyString());
    }

    private void approve() {
        when(approvalService.decide(anyString(), Mockito.eq(true), anyString(), anyString()))
                .thenReturn(new ApprovalDecision(
                        "approval-1", ApprovalStatus.APPROVED, "reviewer", "checked", Instant.now()));
        when(approvalService.find("approval-1")).thenReturn(Optional.of(new ApprovalRecord(
                "approval-1", "run-planner", "incident:inc-1", new ToolCallRequest(
                "floworder_recovery_execute", "execution-1", java.util.Map.of(
                "proposalId", "prop-1", "previewDigest", "preview",
                "recoveryPlanId", "plan-1", "recoveryPlanItemId", "item-1",
                "assessmentDigest", "digest")), "reason",
                ApprovalStatus.APPROVED, "reviewer", "checked", Instant.now(), Instant.now())));
    }

    private IncidentRecoveryPlanRecord plan() {
        Instant now = Instant.now();
        IncidentRecoveryPlanItem item = new IncidentRecoveryPlanItem(
                "item-1", "client-1", "REQUEST_ID", "REQ-1", "REPLAY", "reason",
                List.of("ev-1"), List.of(), RecoveryPlanItemStatus.WAITING_APPROVAL,
                proposal(), "approval-1", "REQUESTED", "NOT_STARTED", "NOT_CONVERGED",
                null, "", now);
        return new IncidentRecoveryPlanRecord(
                "plan-1", "inc-1", "request-1", "run-planner", "digest",
                RecoveryPlanStatus.WAITING_APPROVAL, RecoveryPlanOutcome.READY, null,
                List.of(item), List.of(), 0, now, now);
    }

    private OrderCareRecoveryProposal proposal() {
        return new OrderCareRecoveryProposal(
                "floworder-recovery-proposal-v1", "prop-1", 1, "ACTIVE", "action-1",
                "NOT_STARTED", "NOT_CONVERGED", "case-1", "REQUEST_ID", "REQ-1", "REPLAY",
                "DEAD_LETTER", "dlq-1", "fingerprint", "effects", "warnings", "preview",
                true, List.of("replay dead letter"), List.of("requires approval"), "reason",
                "", "", "", "", Instant.now().plusSeconds(60).toString(),
                Instant.now().toString(), Instant.now().toString());
    }

    private static final class InMemoryPlanStore implements IncidentRecoveryPlanStore {
        private final AtomicReference<IncidentRecoveryPlanRecord> current;

        private InMemoryPlanStore(IncidentRecoveryPlanRecord initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override public IncidentRecoveryPlanRecord create(IncidentRecoveryPlanRecord plan) { current.set(plan); return plan; }
        @Override public Optional<IncidentRecoveryPlanRecord> find(String planId) { return Optional.ofNullable(current.get()); }
        @Override public Optional<IncidentRecoveryPlanRecord> findByRequestKey(String incidentId, String requestKey) { return Optional.ofNullable(current.get()); }
        @Override public List<IncidentRecoveryPlanRecord> listByIncident(String incidentId) { return List.of(current.get()); }

        @Override
        public IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion) {
            IncidentRecoveryPlanRecord previous = current.get();
            if (previous.version() != expectedVersion || !current.compareAndSet(previous, next)) {
                throw new IncidentCasConflictException("CAS conflict");
            }
            return next;
        }
    }
}
