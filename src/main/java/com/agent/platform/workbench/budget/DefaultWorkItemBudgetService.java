package com.agent.platform.workbench.budget;

import com.agent.platform.config.WorkbenchBudgetProperties;
import com.agent.platform.llm.LlmCostCalculator;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.runtime.AgentRunBudgetSnapshot;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.application.RouterFailureObservation;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.dispatch.DispatchResult;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class DefaultWorkItemBudgetService implements WorkItemBudgetGate {

    private final HierarchicalBudgetStore store;
    private final WorkbenchBudgetProperties properties;
    private final LlmCostCalculator costs;
    private final AgentRunStore runs;

    public DefaultWorkItemBudgetService(HierarchicalBudgetStore store,
                                        WorkbenchBudgetProperties properties,
                                        LlmCostCalculator costs,
                                        AgentRunStore runs) {
        this.store = store;
        this.properties = properties;
        this.costs = costs;
        this.runs = runs;
    }

    @Override
    public BudgetReservationHandle reserveRouter(AuthenticatedPrincipal principal,
                                                 AgentWorkItem workItem,
                                                 String operationKey) {
        if (!properties.isEnabled()) {
            if (properties.isAllowLowRiskDegradedMode()) return BudgetReservationHandle.degraded(operationKey);
            throw new BudgetExceededException("BUDGET_CONFIGURATION_UNAVAILABLE", "workbench budget is disabled");
        }
        validatePolicy();
        BudgetAccount account = ensure(principal, workItem);
        BudgetReservation reservation = store.reserve(account.accountId(), operationKey,
                "ROUTER_ATTEMPT", properties.routerAttemptLimit());
        return handle(reservation);
    }

    @Override
    public void settleRouter(BudgetReservationHandle handle, RouterModelResult result) {
        if (!enforced(handle) || result == null) return;
        store.settle(handle.reservationId(), usage(result.modelName(), result.promptTokens(),
                result.completionTokens(), result.latencyMs()));
    }

    @Override
    public void settleRouterFailure(BudgetReservationHandle handle, RouterFailureObservation observation) {
        if (!enforced(handle)) return;
        if (observation == null || (observation.promptTokens() == 0 && observation.completionTokens() == 0
                && observation.latencyMs() == 0)) {
            store.release(handle.reservationId());
            return;
        }
        store.settle(handle.reservationId(), usage(observation.modelName(), observation.promptTokens(),
                observation.completionTokens(), observation.latencyMs()));
    }

    @Override
    public void release(BudgetReservationHandle handle) {
        if (enforced(handle)) store.release(handle.reservationId());
    }

    @Override
    public BudgetReservationHandle reserveTarget(AuthenticatedPrincipal principal,
                                                  String workItemId,
                                                  ExecutionTargetId targetId,
                                                  String operationKey) {
        if (!properties.isEnabled()) {
            if (targetId == ExecutionTargetId.GENERAL_AGENT && properties.isAllowLowRiskDegradedMode()) {
                return BudgetReservationHandle.degraded(operationKey);
            }
            throw new BudgetExceededException("BUDGET_CONFIGURATION_UNAVAILABLE",
                    "budget is required for target " + targetId);
        }
        validatePolicy();
        BudgetAccount account = ensure(principal, workItemId);
        BudgetReservation reservation = store.reserve(account.accountId(), operationKey,
                "TARGET_" + targetId.name(), properties.targetLimit(targetId));
        return handle(reservation);
    }

    @Override
    public void settleTarget(BudgetReservationHandle handle, DispatchResult result) {
        if (!enforced(handle) || result == null || result.linkType() != WorkLinkType.RUN) return;
        runs.find(result.linkedId()).map(this::usage)
                .ifPresent(actual -> store.settle(handle.reservationId(), actual));
    }

    private BudgetAccount ensure(AuthenticatedPrincipal principal, AgentWorkItem workItem) {
        if (principal == null || workItem == null
                || !workItem.tenantId().equals(principal.tenantId())
                || !workItem.ownerPrincipalId().equals(principal.principalId())) {
            throw new IllegalArgumentException("owned WorkItem is required for budget admission");
        }
        return ensure(principal, workItem.workItemId());
    }

    private BudgetAccount ensure(AuthenticatedPrincipal principal, String workItemId) {
        try {
            return store.ensureAccount(new BudgetAccountSpec("WORK_ITEM", workItemId, "",
                    principal.tenantId(), principal.principalId(), properties.workItemLimit()));
        }
        catch (IllegalStateException exception) {
            throw new BudgetExceededException("BUDGET_POLICY_CHANGED", exception.getMessage());
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

    private BudgetLimit usage(String model, long promptTokens, long completionTokens, long latencyMs) {
        LlmUsage usage = new LlmUsage(promptTokens, completionTokens,
                promptTokens + completionTokens, 0, 0, model == null ? "" : model, "workbench-budget");
        return new BudgetLimit(1, Math.max(0, promptTokens) + Math.max(0, completionTokens),
                0, Math.max(0, latencyMs), Math.max(0, costs.estimate(usage)));
    }

    private BudgetLimit usage(AgentRunRecord record) {
        AgentRunBudgetSnapshot snapshot = record == null ? null : record.budgetSnapshot();
        if (snapshot == null) return new BudgetLimit(0, 0, 0, 0, 0);
        long maximum = record.executionProfile() == null ? 0
                : record.executionProfile().limits().maxRunDurationMillis();
        long elapsed = maximum == 0 ? 0 : Math.max(0, maximum - snapshot.remainingExecutionMillis());
        return new BudgetLimit(snapshot.modelCalls(), snapshot.inputTokens() + snapshot.outputTokens(),
                snapshot.toolCalls(), elapsed, snapshot.estimatedCost());
    }

    private BudgetReservationHandle handle(BudgetReservation value) {
        return new BudgetReservationHandle(value.reservationId(), value.accountId(), value.operationKey(), true);
    }

    private boolean enforced(BudgetReservationHandle handle) {
        return handle != null && handle.enforced() && handle.reservationId() != null
                && !handle.reservationId().isBlank();
    }
}
