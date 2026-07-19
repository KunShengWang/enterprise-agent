package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.RecoveryConvergenceChecker;
import com.agent.platform.ordercare.application.RecoveryOutcomeReconciler;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.config.IncidentWorkerIdentity;
import com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDecisionRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryItemLeaseClaim;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.model.OrderCareRecoveryReconciliationResult;
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
                properties, store, approvalService, flowOrderClient, convergenceChecker, outcomeReconciler,
                new IncidentWorkerIdentity(properties));
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

    @Test
    void staleExecutionTakeoverReconcilesOriginalActionAndFencesOldOwner() {
        IncidentCommandProperties phase3 = new IncidentCommandProperties();
        phase3.setEnabled(true);
        phase3.setRecoveryPlannerEnabled(true);
        phase3.setPhase3Enabled(true);
        phase3.setInstanceId("worker-b");
        IncidentRecoveryPlanRecord base = plan();
        IncidentRecoveryPlanItem stale = leaseCopy(
                base.items().get(0), RecoveryPlanItemStatus.EXECUTING,
                "worker-a", 1, Instant.now().minusSeconds(1), 0);
        IncidentRecoveryPlanRecord stalePlan = new IncidentRecoveryPlanRecord(
                base.planId(), base.incidentId(), base.requestKey(), base.plannerRunId(), base.assessmentDigest(),
                RecoveryPlanStatus.EXECUTING, RecoveryPlanOutcome.READY, base.draft(), List.of(stale),
                base.validationErrors(), 1, base.createdAt(), Instant.now());
        store = new InMemoryPlanStore(stalePlan);
        service = new IncidentRecoveryExecutionService(
                phase3, store, approvalService, flowOrderClient, convergenceChecker, outcomeReconciler,
                new IncidentWorkerIdentity(phase3));
        approve();
        when(flowOrderClient.getProposal(anyString(), anyString())).thenReturn(proposal());
        OrderCareConvergenceResult convergence = new OrderCareConvergenceResult(
                "prop-1", "RESOLVED", 1, "EXECUTED", "SUBMITTED", "RESOLVED", true, true, true);
        when(outcomeReconciler.reconcile(any(), any(), anyString(), anyString(), Mockito.eq(true)))
                .thenReturn(new OrderCareRecoveryReconciliationResult(
                        "RESOLVED", 1, true, false, null, convergence));

        IncidentRecoveryPlanRecord result = service.recoverStaleExecution("plan-1", "item-1");

        assertEquals(RecoveryPlanItemStatus.RESOLVED, result.items().get(0).status());
        assertEquals(2, result.items().get(0).fencingToken());
        assertEquals(1, result.items().get(0).takeoverCount());
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
                null, "", "", 0, null, null, 0, now);
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

    private IncidentRecoveryPlanItem leaseCopy(IncidentRecoveryPlanItem item,
                                               RecoveryPlanItemStatus status,
                                               String owner,
                                               long token,
                                               Instant leaseUntil,
                                               int takeoverCount) {
        return new IncidentRecoveryPlanItem(
                item.itemId(), item.clientItemKey(), item.identifierType(), item.identifierValue(), item.actionType(),
                item.suggestedReason(), item.evidenceIds(), item.conflictIds(), status, item.proposal(), item.approvalId(),
                "APPROVED", item.actionStatus(), item.caseOutcome(), item.convergence(), item.lastError(), owner, token,
                leaseUntil, Instant.now(), takeoverCount, Instant.now());
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

        @Override
        public RecoveryItemLeaseClaim claimItem(String planId, String itemId, String owner,
                                                Instant leaseUntil, boolean allowExpiredTakeover) {
            IncidentRecoveryPlanRecord plan = current.get();
            IncidentRecoveryPlanItem item = plan.items().get(0);
            boolean takeover = item.status() == RecoveryPlanItemStatus.EXECUTING
                    && (item.leaseUntil() == null || !item.leaseUntil().isAfter(Instant.now()));
            if (!takeover && item.status() != RecoveryPlanItemStatus.WAITING_APPROVAL) {
                return new RecoveryItemLeaseClaim(plan, item, false, false);
            }
            IncidentRecoveryPlanItem claimed = new IncidentRecoveryPlanItem(
                    item.itemId(), item.clientItemKey(), item.identifierType(), item.identifierValue(), item.actionType(),
                    item.suggestedReason(), item.evidenceIds(), item.conflictIds(), RecoveryPlanItemStatus.EXECUTING,
                    item.proposal(), item.approvalId(), item.approvalStatus(), item.actionStatus(), item.caseOutcome(),
                    item.convergence(), item.lastError(), owner, item.fencingToken() + 1, leaseUntil, Instant.now(),
                    takeover ? item.takeoverCount() + 1 : item.takeoverCount(), Instant.now());
            IncidentRecoveryPlanRecord next = new IncidentRecoveryPlanRecord(
                    plan.planId(), plan.incidentId(), plan.requestKey(), plan.plannerRunId(), plan.assessmentDigest(),
                    RecoveryPlanStatus.EXECUTING, RecoveryPlanOutcome.READY, plan.draft(), List.of(claimed),
                    plan.validationErrors(), plan.version() + 1, plan.createdAt(), Instant.now());
            current.set(next);
            return new RecoveryItemLeaseClaim(next, claimed, true, takeover);
        }

        @Override
        public IncidentRecoveryPlanRecord renewItemLease(String planId, String itemId, String owner,
                                                         long fencingToken, Instant leaseUntil) {
            return current.get();
        }

        @Override
        public IncidentRecoveryPlanRecord updateItemFenced(String planId, IncidentRecoveryPlanItem replacement,
                                                           String owner, long fencingToken) {
            IncidentRecoveryPlanRecord plan = current.get();
            IncidentRecoveryPlanItem existing = plan.items().get(0);
            if (!owner.equals(existing.executionOwner()) || fencingToken != existing.fencingToken()) {
                throw new IncidentCasConflictException("stale token");
            }
            RecoveryPlanOutcome outcome = replacement.status() == RecoveryPlanItemStatus.RESOLVED
                    ? RecoveryPlanOutcome.RESOLVED : RecoveryPlanOutcome.MANUAL_REVIEW;
            IncidentRecoveryPlanRecord next = new IncidentRecoveryPlanRecord(
                    plan.planId(), plan.incidentId(), plan.requestKey(), plan.plannerRunId(), plan.assessmentDigest(),
                    RecoveryPlanStatus.COMPLETED, outcome, plan.draft(), List.of(replacement), plan.validationErrors(),
                    plan.version() + 1, plan.createdAt(), Instant.now());
            current.set(next);
            return next;
        }
    }
}
