package com.agent.platform.workbench.budget;

import java.util.Optional;

public interface HierarchicalBudgetStore {
    BudgetAccount ensureAccount(BudgetAccountSpec spec);
    BudgetReservation reserve(String accountId, String operationKey, String category, BudgetLimit amount);
    BudgetReservation settle(String reservationId, BudgetLimit actual);
    BudgetReservation release(String reservationId);
    Optional<BudgetAccount> findAccount(String ownerType, String ownerId);
    Optional<BudgetReservation> findReservation(String accountId, String operationKey);
    Optional<BudgetReservation> findReservedByCategory(String accountId, String category);
}
