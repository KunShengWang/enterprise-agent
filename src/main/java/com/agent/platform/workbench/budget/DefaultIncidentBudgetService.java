package com.agent.platform.workbench.budget;

import com.agent.platform.config.WorkbenchBudgetProperties;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import com.agent.platform.runtime.AgentRunLimits;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentRunStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class DefaultIncidentBudgetService implements IncidentBudgetGate {

    private final HierarchicalBudgetStore store;
    private final WorkbenchBudgetProperties properties;
    private final AgentRunStore runs;

    public DefaultIncidentBudgetService(HierarchicalBudgetStore store,
                                        WorkbenchBudgetProperties properties,
                                        AgentRunStore runs) {
        this.store = store;
        this.properties = properties;
        this.runs = runs;
    }

    @Override
    public void initializeIncident(String incidentId, String parentWorkItemId) {
        requireEnabled("Incident");
        validatePolicy();
        ensure("INCIDENT", incidentId, parentWorkItemId, properties.incidentAggregateLimit());
    }

    @Override
    public void initializeRecoveryPlan(String planId, String parentWorkItemId) {
        requireEnabled("Recovery Plan");
        validatePolicy();
        ensure("RECOVERY_PLAN", planId, parentWorkItemId, properties.recoveryPlanAggregateLimit());
    }

    @Override
    public IncidentBudgetReservation reserveIncidentRun(String incidentId,
                                                         String operationKey,
                                                         String role,
                                                         AgentExecutionProfile profile) {
        return reserve("INCIDENT", incidentId, operationKey, role, profile);
    }

    @Override
    public IncidentBudgetReservation reserveRecoveryPlanRun(String planId,
                                                             String operationKey,
                                                             String role,
                                                             AgentExecutionProfile profile) {
        return reserve("RECOVERY_PLAN", planId, operationKey, role, profile);
    }

    @Override
    public void settle(IncidentBudgetReservation reservation, AgentRuntimeResult result) {
        if (!enforced(reservation) || result == null || result.budget() == null) return;
        AgentRunBudgetSnapshot snapshot = result.budget();
        long elapsed = Math.max(0, reservation.maxDurationMillis() - snapshot.remainingExecutionMillis());
        store.settle(reservation.handle().reservationId(), new BudgetLimit(
                snapshot.modelCalls(), snapshot.inputTokens() + snapshot.outputTokens(),
                snapshot.toolCalls(), elapsed, snapshot.estimatedCost()));
    }

    @Override
    public void settleStored(IncidentBudgetReservation reservation, String runId) {
        if (!enforced(reservation) || runId == null || runId.isBlank()) return;
        runs.find(runId).ifPresent(record -> {
            AgentRunBudgetSnapshot snapshot = record.budgetSnapshot();
            if (snapshot == null) return;
            long elapsed = Math.max(0, reservation.maxDurationMillis() - snapshot.remainingExecutionMillis());
            store.settle(reservation.handle().reservationId(), new BudgetLimit(
                    snapshot.modelCalls(), snapshot.inputTokens() + snapshot.outputTokens(),
                    snapshot.toolCalls(), elapsed, snapshot.estimatedCost()));
        });
    }

    @Override
    public void release(IncidentBudgetReservation reservation) {
        if (enforced(reservation)) store.release(reservation.handle().reservationId());
    }

    @Override
    public void completeIncident(String incidentId) {
        completeChild("INCIDENT", incidentId, "TARGET_INCIDENT_INVESTIGATION");
    }

    @Override
    public void completeRecoveryPlan(String planId) {
        completeChild("RECOVERY_PLAN", planId, "TARGET_INCIDENT_RECOVERY_PLAN");
    }

    @Override
    public IncidentBudgetReservation reserveRecoveryPlanTool(String planId,
                                                              String operationKey,
                                                              String category) {
        requireEnabled("RECOVERY_PLAN");
        BudgetAccount account = store.findAccount("RECOVERY_PLAN", planId)
                .orElseThrow(() -> new BudgetExceededException(
                        "BUDGET_CONFIGURATION_UNAVAILABLE", "Recovery Plan budget account is missing"));
        BudgetReservation value = store.reserve(account.accountId(), operationKey,
                "TOOL_" + category, new BudgetLimit(0, 0, 1, 0, 0));
        return new IncidentBudgetReservation(new BudgetReservationHandle(
                value.reservationId(), value.accountId(), value.operationKey(), true), 0);
    }

    @Override
    public void settleDeterministicTool(IncidentBudgetReservation reservation, long durationMillis) {
        if (!enforced(reservation)) return;
        store.settle(reservation.handle().reservationId(),
                new BudgetLimit(0, 0, 1, Math.max(0, durationMillis), 0));
    }

    private void completeChild(String ownerType, String ownerId, String parentCategory) {
        BudgetAccount child = store.findAccount(ownerType, ownerId).orElse(null);
        if (child == null || child.parentAccountId().isBlank() || !child.reserved().zero()) return;
        store.findReservedByCategory(child.parentAccountId(), parentCategory)
                .ifPresent(parent -> store.settle(parent.reservationId(), child.consumed()));
    }

    private IncidentBudgetReservation reserve(String ownerType,
                                               String ownerId,
                                               String operationKey,
                                               String role,
                                               AgentExecutionProfile profile) {
        requireEnabled(ownerType);
        if (profile == null) throw new BudgetExceededException(
                "BUDGET_CONFIGURATION_UNAVAILABLE", "execution profile is required for budget admission");
        BudgetAccount account = store.findAccount(ownerType, ownerId)
                .orElseThrow(() -> new BudgetExceededException(
                        "BUDGET_CONFIGURATION_UNAVAILABLE", "budget account is missing for " + ownerType));
        BudgetLimit amount = limit(profile.limits());
        if (!amount.fitsWithin(account.maximum())) {
            throw new BudgetExceededException("BUDGET_POLICY_INVALID",
                    "role profile exceeds aggregate budget: " + role);
        }
        BudgetReservation value = store.reserve(account.accountId(), operationKey,
                "ROLE_" + role, amount);
        return new IncidentBudgetReservation(new BudgetReservationHandle(
                value.reservationId(), value.accountId(), value.operationKey(), true),
                profile.limits().maxRunDurationMillis());
    }

    private BudgetAccount ensure(String ownerType,
                                 String ownerId,
                                 String parentWorkItemId,
                                 BudgetLimit maximum) {
        BudgetAccount parent = parentWorkItemId == null || parentWorkItemId.isBlank()
                ? null : store.findAccount("WORK_ITEM", parentWorkItemId).orElseThrow(() ->
                new BudgetExceededException("BUDGET_CONFIGURATION_UNAVAILABLE",
                        "parent WorkItem budget account is missing"));
        if (parent != null && !maximum.fitsWithin(parent.reserved())) {
            throw new BudgetExceededException("BUDGET_PARENT_RESERVATION_MISSING",
                    "parent WorkItem has not reserved the aggregate child budget");
        }
        try {
            return store.ensureAccount(new BudgetAccountSpec(ownerType, ownerId,
                    parent == null ? "" : parent.accountId(),
                    parent == null ? "internal" : parent.tenantId(),
                    parent == null ? ownerType.toLowerCase() + "-orchestrator" : parent.ownerPrincipalId(),
                    maximum));
        }
        catch (IllegalStateException exception) {
            throw new BudgetExceededException("BUDGET_POLICY_CHANGED", exception.getMessage());
        }
    }

    private BudgetLimit limit(AgentRunLimits limits) {
        return new BudgetLimit(limits.maxModelCalls(), limits.maxInputTokens() + limits.maxOutputTokens(),
                limits.maxToolCalls(), limits.maxRunDurationMillis(), limits.maxEstimatedCost());
    }

    private void requireEnabled(String scope) {
        if (!properties.isEnabled()) {
            throw new BudgetExceededException("BUDGET_CONFIGURATION_UNAVAILABLE",
                    scope + " budget enforcement is disabled");
        }
    }

    private void validatePolicy() {
        try {
            properties.validateHierarchy();
        }
        catch (RuntimeException exception) {
            throw new BudgetExceededException("BUDGET_POLICY_INVALID", exception.getMessage());
        }
    }

    private boolean enforced(IncidentBudgetReservation reservation) {
        return reservation != null && reservation.handle() != null && reservation.handle().enforced();
    }
}
