package com.agent.platform.workbench.budget;

import java.time.Instant;

public record BudgetReservation(
        String reservationId,
        String accountId,
        String operationKey,
        String category,
        String status,
        BudgetLimit reserved,
        BudgetLimit consumed,
        Instant createdAt,
        Instant settledAt
) {
}
