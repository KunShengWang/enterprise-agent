package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.RecoveryConvergenceChecker;
import com.agent.platform.ordercare.application.RecoveryOutcomeReconciler;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDecisionRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.model.OrderCareRecoveryReconciliationResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class IncidentRecoveryExecutionService {

    private final IncidentCommandProperties properties;
    private final IncidentRecoveryPlanStore planStore;
    private final ApprovalService approvalService;
    private final FlowOrderClient flowOrderClient;
    private final RecoveryConvergenceChecker convergenceChecker;
    private final RecoveryOutcomeReconciler outcomeReconciler;

    public IncidentRecoveryExecutionService(IncidentCommandProperties properties,
                                            IncidentRecoveryPlanStore planStore,
                                            ApprovalService approvalService,
                                            FlowOrderClient flowOrderClient,
                                            RecoveryConvergenceChecker convergenceChecker,
                                            RecoveryOutcomeReconciler outcomeReconciler) {
        this.properties = properties;
        this.planStore = planStore;
        this.approvalService = approvalService;
        this.flowOrderClient = flowOrderClient;
        this.convergenceChecker = convergenceChecker;
        this.outcomeReconciler = outcomeReconciler;
    }

    public IncidentRecoveryPlanRecord decideAndExecute(String planId,
                                                       String itemId,
                                                       RecoveryPlanDecisionRequest request) {
        requireEnabled();
        IncidentRecoveryPlanRecord initial = requirePlan(planId);
        IncidentRecoveryPlanItem initialItem = requireItem(initial, itemId);
        if (initialItem.approvalId().isBlank()) {
            throw new IllegalStateException("recovery plan item has no approval request");
        }
        ApprovalDecision decision = approvalService.decide(
                initialItem.approvalId(),
                request != null && request.approved(),
                request == null ? "" : request.reviewer(),
                request == null ? "" : request.reason());
        if (decision.status() == ApprovalStatus.REJECTED) {
            return projectDecision(planId, itemId, "REJECTED", RecoveryPlanItemStatus.REJECTED,
                    "recovery proposal rejected by human reviewer");
        }
        if (decision.status() != ApprovalStatus.APPROVED) {
            return projectDecision(planId, itemId, decision.status().name(),
                    RecoveryPlanItemStatus.MANUAL_REVIEW,
                    "approval is not executable: " + decision.status());
        }

        ClaimedItem claimed = claimExecution(planId, itemId);
        if (!claimed.claimed()) {
            return claimed.plan();
        }
        return execute(claimed.plan(), claimed.item(), decision);
    }

    private IncidentRecoveryPlanRecord execute(IncidentRecoveryPlanRecord plan,
                                               IncidentRecoveryPlanItem item,
                                               ApprovalDecision decision) {
        OrderCareRecoveryProposal immutable = item.proposal();
        try {
            ApprovalRecord approval = approvalService.find(item.approvalId())
                    .filter(record -> record.status() == ApprovalStatus.APPROVED)
                    .orElseThrow(() -> new IllegalStateException("approved record disappeared"));
            ensureApprovalBound(plan, item, approval);
            OrderCareRecoveryProposal current = flowOrderClient.getProposal(
                    immutable.proposalId(), traceId(plan, item, "proposal-check"));
            ensureSamePreview(immutable, current);
            if (!"ACTIVE".equals(current.proposalStatus()) || !Boolean.TRUE.equals(current.canExecute())) {
                return finish(plan.planId(), item.itemId(), RecoveryPlanItemStatus.MANUAL_REVIEW,
                        approval.status().name(), current.actionStatus(), current.caseOutcome(), null,
                        "Proposal expired or became ineligible before execution");
            }
            OrderCareProposalExecuteCommand command = new OrderCareProposalExecuteCommand(
                    current.proposalId(), current.proposalVersion(), current.stateFingerprint(),
                    current.effectsDigest(), current.warningsDigest(), current.previewDigest(),
                    approval.approvalId(), approval.reviewer(), approval.decisionReason(),
                    "incident-recovery-plan:" + plan.planId() + ":" + item.itemId());
            try {
                flowOrderClient.executeProposal(command, traceId(plan, item, "execute"));
            } catch (FlowOrderApiException exception) {
                if (!exception.outcomeUnknown()) {
                    return finish(plan.planId(), item.itemId(), RecoveryPlanItemStatus.MANUAL_REVIEW,
                            "APPROVED", current.actionStatus(), current.caseOutcome(), null,
                            "FlowOrder rejected approved Proposal: " + exception.getMessage());
                }
                OrderCareRecoveryReconciliationResult reconciliation = outcomeReconciler.reconcile(
                        immutable, command,
                        "incident-recovery-plan:" + plan.planId(),
                        traceId(plan, item, "reconcile"), true);
                OrderCareConvergenceResult convergence = reconciliation.convergence();
                boolean resolved = "RESOLVED".equals(reconciliation.status()) && convergence != null;
                return finish(plan.planId(), item.itemId(),
                        resolved ? RecoveryPlanItemStatus.RESOLVED : RecoveryPlanItemStatus.MANUAL_REVIEW,
                        "APPROVED",
                        convergence == null ? safeAction(reconciliation) : convergence.actionStatus(),
                        convergence == null ? "MANUAL_REVIEW" : convergence.caseOutcome(),
                        convergence,
                        resolved ? "" : "UNKNOWN execution could not be proven resolved");
            }
            OrderCareConvergenceResult convergence = convergenceChecker.await(
                    current.proposalId(), traceId(plan, item, "convergence"));
            boolean resolved = "RESOLVED".equals(convergence.status());
            return finish(plan.planId(), item.itemId(),
                    resolved ? RecoveryPlanItemStatus.RESOLVED : RecoveryPlanItemStatus.MANUAL_REVIEW,
                    "APPROVED", convergence.actionStatus(), convergence.caseOutcome(), convergence,
                    resolved ? "" : "business convergence was not proven");
        } catch (RuntimeException exception) {
            return finish(plan.planId(), item.itemId(), RecoveryPlanItemStatus.MANUAL_REVIEW,
                    decision.status().name(), immutable == null ? "NOT_STARTED" : immutable.actionStatus(),
                    immutable == null ? "NOT_CONVERGED" : immutable.caseOutcome(), null,
                    "recovery execution coordination failed: " + exception.getClass().getSimpleName());
        }
    }

    private ClaimedItem claimExecution(String planId, String itemId) {
        for (int attempt = 0; attempt < 4; attempt++) {
            IncidentRecoveryPlanRecord plan = requirePlan(planId);
            IncidentRecoveryPlanItem item = requireItem(plan, itemId);
            if (item.status() == RecoveryPlanItemStatus.EXECUTING || item.status().terminal()) {
                return new ClaimedItem(plan, item, false);
            }
            if (item.status() != RecoveryPlanItemStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("recovery plan item is not waiting approval: " + item.status());
            }
            IncidentRecoveryPlanItem claimed = copy(item, RecoveryPlanItemStatus.EXECUTING,
                    "APPROVED", item.actionStatus(), item.caseOutcome(), item.convergence(), "");
            try {
                IncidentRecoveryPlanRecord updated = updateItem(plan, claimed);
                return new ClaimedItem(updated, claimed, true);
            } catch (com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException ignored) {
                // A different item or duplicate decision advanced the same aggregate. Reload and retry.
            }
        }
        throw new IllegalStateException("recovery plan execution claim contention exceeded");
    }

    private IncidentRecoveryPlanRecord projectDecision(String planId,
                                                       String itemId,
                                                       String approvalStatus,
                                                       RecoveryPlanItemStatus itemStatus,
                                                       String error) {
        for (int attempt = 0; attempt < 4; attempt++) {
            IncidentRecoveryPlanRecord plan = requirePlan(planId);
            IncidentRecoveryPlanItem item = requireItem(plan, itemId);
            if (item.status().terminal()) {
                return plan;
            }
            try {
                return updateItem(plan, copy(item, itemStatus, approvalStatus,
                        item.actionStatus(), item.caseOutcome(), item.convergence(), error));
            } catch (com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException ignored) {
                // Reload aggregate.
            }
        }
        throw new IllegalStateException("recovery plan decision projection contention exceeded");
    }

    private IncidentRecoveryPlanRecord finish(String planId,
                                              String itemId,
                                              RecoveryPlanItemStatus status,
                                              String approvalStatus,
                                              String actionStatus,
                                              String caseOutcome,
                                              OrderCareConvergenceResult convergence,
                                              String error) {
        for (int attempt = 0; attempt < 4; attempt++) {
            IncidentRecoveryPlanRecord plan = requirePlan(planId);
            IncidentRecoveryPlanItem item = requireItem(plan, itemId);
            if (item.status().terminal()) {
                return plan;
            }
            try {
                return updateItem(plan, copy(item, status, approvalStatus,
                        actionStatus, caseOutcome, convergence, error));
            } catch (com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException ignored) {
                // Reload aggregate; FlowOrder idempotency remains authoritative.
            }
        }
        throw new IllegalStateException("recovery plan result persistence contention exceeded");
    }

    private IncidentRecoveryPlanRecord updateItem(IncidentRecoveryPlanRecord plan,
                                                  IncidentRecoveryPlanItem replacement) {
        List<IncidentRecoveryPlanItem> items = new ArrayList<>(plan.items());
        boolean replaced = false;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).itemId().equals(replacement.itemId())) {
                items.set(index, replacement);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("recovery plan item not found: " + replacement.itemId());
        }
        DerivedState derived = derive(items);
        IncidentRecoveryPlanRecord next = new IncidentRecoveryPlanRecord(
                plan.planId(), plan.incidentId(), plan.requestKey(), plan.plannerRunId(),
                plan.assessmentDigest(), derived.status(), derived.outcome(), plan.draft(), items,
                plan.validationErrors(), plan.version() + 1, plan.createdAt(), Instant.now());
        return planStore.update(next, plan.version());
    }

    private DerivedState derive(List<IncidentRecoveryPlanItem> items) {
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.EXECUTING)) {
            return new DerivedState(RecoveryPlanStatus.EXECUTING, RecoveryPlanOutcome.READY);
        }
        if (items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL)) {
            return new DerivedState(RecoveryPlanStatus.WAITING_APPROVAL, RecoveryPlanOutcome.READY);
        }
        long resolved = items.stream().filter(item -> item.status() == RecoveryPlanItemStatus.RESOLVED).count();
        boolean manual = items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.MANUAL_REVIEW
                || item.status() == RecoveryPlanItemStatus.FAILED);
        if (resolved == items.size() && !items.isEmpty()) {
            return new DerivedState(RecoveryPlanStatus.COMPLETED, RecoveryPlanOutcome.RESOLVED);
        }
        if (resolved > 0) {
            return new DerivedState(RecoveryPlanStatus.COMPLETED, RecoveryPlanOutcome.PARTIAL);
        }
        if (manual) {
            return new DerivedState(RecoveryPlanStatus.COMPLETED, RecoveryPlanOutcome.MANUAL_REVIEW);
        }
        return new DerivedState(RecoveryPlanStatus.COMPLETED, RecoveryPlanOutcome.REJECTED);
    }

    private IncidentRecoveryPlanItem copy(IncidentRecoveryPlanItem item,
                                          RecoveryPlanItemStatus status,
                                          String approvalStatus,
                                          String actionStatus,
                                          String caseOutcome,
                                          OrderCareConvergenceResult convergence,
                                          String error) {
        return new IncidentRecoveryPlanItem(
                item.itemId(), item.clientItemKey(), item.identifierType(), item.identifierValue(),
                item.actionType(), item.suggestedReason(), item.evidenceIds(), item.conflictIds(),
                status, item.proposal(), item.approvalId(), approvalStatus, actionStatus,
                caseOutcome, convergence, error, Instant.now());
    }

    private void ensureSamePreview(OrderCareRecoveryProposal expected, OrderCareRecoveryProposal current) {
        if (expected == null || current == null
                || !Objects.equals(expected.proposalId(), current.proposalId())
                || !Objects.equals(expected.actionRequestId(), current.actionRequestId())
                || !Objects.equals(expected.proposalVersion(), current.proposalVersion())
                || !Objects.equals(expected.stateFingerprint(), current.stateFingerprint())
                || !Objects.equals(expected.effectsDigest(), current.effectsDigest())
                || !Objects.equals(expected.warningsDigest(), current.warningsDigest())
                || !Objects.equals(expected.previewDigest(), current.previewDigest())) {
            throw new IllegalArgumentException("FlowOrder Proposal changed after human-visible preview");
        }
    }

    private void ensureApprovalBound(IncidentRecoveryPlanRecord plan,
                                     IncidentRecoveryPlanItem item,
                                     ApprovalRecord approval) {
        if (approval.toolCallRequest() == null
                || !Objects.equals(item.approvalId(), approval.approvalId())
                || !Objects.equals(plan.plannerRunId(), approval.runId())
                || !Objects.equals(item.proposal().proposalId(),
                String.valueOf(approval.toolCallRequest().arguments().get("proposalId")))
                || !Objects.equals(item.proposal().previewDigest(),
                String.valueOf(approval.toolCallRequest().arguments().get("previewDigest")))
                || !Objects.equals(plan.planId(),
                String.valueOf(approval.toolCallRequest().arguments().get("recoveryPlanId")))
                || !Objects.equals(item.itemId(),
                String.valueOf(approval.toolCallRequest().arguments().get("recoveryPlanItemId")))
                || !Objects.equals(plan.assessmentDigest(),
                String.valueOf(approval.toolCallRequest().arguments().get("assessmentDigest")))) {
            throw new IllegalArgumentException("approval is not bound to this immutable recovery plan item");
        }
    }

    private String safeAction(OrderCareRecoveryReconciliationResult result) {
        return result.action() == null ? "UNKNOWN" : result.action().actionStatus();
    }

    private String traceId(IncidentRecoveryPlanRecord plan,
                           IncidentRecoveryPlanItem item,
                           String operation) {
        return plan.planId() + ":" + item.itemId() + ":" + operation;
    }

    private IncidentRecoveryPlanRecord requirePlan(String planId) {
        return planStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("recovery plan not found: " + planId));
    }

    private IncidentRecoveryPlanItem requireItem(IncidentRecoveryPlanRecord plan, String itemId) {
        return plan.items().stream()
                .filter(item -> item.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("recovery plan item not found: " + itemId));
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.isRecoveryPlannerEnabled()) {
            throw new IllegalStateException("Incident Recovery Planner is disabled");
        }
    }

    private record ClaimedItem(IncidentRecoveryPlanRecord plan,
                               IncidentRecoveryPlanItem item,
                               boolean claimed) { }

    private record DerivedState(RecoveryPlanStatus status, RecoveryPlanOutcome outcome) { }
}
