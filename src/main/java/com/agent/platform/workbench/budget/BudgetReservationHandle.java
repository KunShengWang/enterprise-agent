package com.agent.platform.workbench.budget;

public record BudgetReservationHandle(
        String reservationId,
        String accountId,
        String operationKey,
        boolean enforced
) {
    public static BudgetReservationHandle degraded(String operationKey) {
        return new BudgetReservationHandle("", "", operationKey == null ? "" : operationKey, false);
    }
}
