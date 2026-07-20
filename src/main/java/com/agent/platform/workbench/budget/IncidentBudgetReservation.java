package com.agent.platform.workbench.budget;

public record IncidentBudgetReservation(
        BudgetReservationHandle handle,
        long maxDurationMillis
) {
    public static IncidentBudgetReservation degraded(String operationKey) {
        return new IncidentBudgetReservation(BudgetReservationHandle.degraded(operationKey), 0);
    }
}
