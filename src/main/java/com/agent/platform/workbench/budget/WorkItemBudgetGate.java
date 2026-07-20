package com.agent.platform.workbench.budget;

import com.agent.platform.workbench.application.RouterFailureObservation;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.dispatch.DispatchResult;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;

public interface WorkItemBudgetGate {

    WorkItemBudgetGate NOOP = new WorkItemBudgetGate() {
        @Override public BudgetReservationHandle reserveRouter(AuthenticatedPrincipal p, AgentWorkItem w, String key) {
            return BudgetReservationHandle.degraded(key);
        }
        @Override public void settleRouter(BudgetReservationHandle h, RouterModelResult r) { }
        @Override public void settleRouterFailure(BudgetReservationHandle h, RouterFailureObservation o) { }
        @Override public void release(BudgetReservationHandle h) { }
        @Override public BudgetReservationHandle reserveTarget(AuthenticatedPrincipal p, String w,
                                                                ExecutionTargetId t, String key) {
            return BudgetReservationHandle.degraded(key);
        }
        @Override public void settleTarget(BudgetReservationHandle h, DispatchResult r) { }
    };

    BudgetReservationHandle reserveRouter(AuthenticatedPrincipal principal, AgentWorkItem workItem, String operationKey);
    void settleRouter(BudgetReservationHandle handle, RouterModelResult result);
    void settleRouterFailure(BudgetReservationHandle handle, RouterFailureObservation observation);
    void release(BudgetReservationHandle handle);
    BudgetReservationHandle reserveTarget(AuthenticatedPrincipal principal,
                                          String workItemId,
                                          ExecutionTargetId targetId,
                                          String operationKey);
    void settleTarget(BudgetReservationHandle handle, DispatchResult result);
}
