package com.agent.platform.workbench.budget;

import java.time.Instant;

public record BudgetAccount(
        String accountId,
        String ownerType,
        String ownerId,
        String parentAccountId,
        String tenantId,
        String ownerPrincipalId,
        String status,
        BudgetLimit maximum,
        BudgetLimit reserved,
        BudgetLimit consumed,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
