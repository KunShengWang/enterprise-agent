package com.agent.platform.workbench.budget;

public record BudgetAccountSpec(
        String ownerType,
        String ownerId,
        String parentAccountId,
        String tenantId,
        String ownerPrincipalId,
        BudgetLimit maximum
) {
}
