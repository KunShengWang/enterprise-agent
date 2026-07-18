package com.agent.platform.ordercare.incident.model;

import java.time.LocalDateTime;
import java.util.List;

public record IncidentInventoryFacts(
        Integer recordCount,
        Integer distinctRequestIdCount,
        Integer unreleasedDistinctRequestIdCount,
        List<String> requestIds,
        List<String> unreleasedRequestIds,
        List<Long> invariantViolationStockItemIds,
        List<InventoryFact> items
) {
    public IncidentInventoryFacts {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        unreleasedRequestIds = unreleasedRequestIds == null ? List.of() : List.copyOf(unreleasedRequestIds);
        invariantViolationStockItemIds = invariantViolationStockItemIds == null
                ? List.of()
                : List.copyOf(invariantViolationStockItemIds);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record InventoryFact(
            String requestId,
            String deductNo,
            Integer deductStatus,
            Integer quantity,
            Long stockItemId,
            Boolean stockItemFound,
            Integer totalStock,
            Integer availableStock,
            Integer lockedStock,
            Integer soldStock,
            Boolean inventoryInvariantOk,
            LocalDateTime updatedAt
    ) {
    }
}
