package com.agent.platform.workbench.budget;

import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRuntimeResult;

public interface IncidentBudgetGate {

    IncidentBudgetGate NOOP = new IncidentBudgetGate() {
        @Override public void initializeIncident(String incidentId, String parentWorkItemId) { }
        @Override public void initializeRecoveryPlan(String planId, String parentWorkItemId) { }
        @Override public IncidentBudgetReservation reserveIncidentRun(String id, String key, String role,
                                                                       AgentExecutionProfile profile) {
            return IncidentBudgetReservation.degraded(key);
        }
        @Override public IncidentBudgetReservation reserveRecoveryPlanRun(String id, String key, String role,
                                                                           AgentExecutionProfile profile) {
            return IncidentBudgetReservation.degraded(key);
        }
        @Override public void settle(IncidentBudgetReservation reservation, AgentRuntimeResult result) { }
        @Override public void settleStored(IncidentBudgetReservation reservation, String runId) { }
        @Override public void release(IncidentBudgetReservation reservation) { }
        @Override public void completeIncident(String incidentId) { }
        @Override public void completeRecoveryPlan(String planId) { }
        @Override public IncidentBudgetReservation reserveRecoveryPlanTool(String planId, String key, String category) {
            return IncidentBudgetReservation.degraded(key);
        }
        @Override public void settleDeterministicTool(IncidentBudgetReservation reservation, long durationMillis) { }
    };

    void initializeIncident(String incidentId, String parentWorkItemId);
    void initializeRecoveryPlan(String planId, String parentWorkItemId);
    IncidentBudgetReservation reserveIncidentRun(String incidentId,
                                                  String operationKey,
                                                  String role,
                                                  AgentExecutionProfile profile);
    IncidentBudgetReservation reserveRecoveryPlanRun(String planId,
                                                      String operationKey,
                                                      String role,
                                                      AgentExecutionProfile profile);
    void settle(IncidentBudgetReservation reservation, AgentRuntimeResult result);
    void settleStored(IncidentBudgetReservation reservation, String runId);
    void release(IncidentBudgetReservation reservation);
    void completeIncident(String incidentId);
    void completeRecoveryPlan(String planId);
    IncidentBudgetReservation reserveRecoveryPlanTool(String planId, String operationKey, String category);
    void settleDeterministicTool(IncidentBudgetReservation reservation, long durationMillis);
}
