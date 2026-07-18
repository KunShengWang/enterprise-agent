package com.agent.platform.ordercare.incident.model;

import java.time.LocalDateTime;
import java.util.List;

public record IncidentOrderFacts(
        Integer recordCount,
        Integer distinctRequestIdCount,
        Integer terminalDistinctRequestIdCount,
        List<String> requestIds,
        List<String> terminalRequestIds,
        List<OrderFact> items
) {
    public IncidentOrderFacts {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        terminalRequestIds = terminalRequestIds == null ? List.of() : List.copyOf(terminalRequestIds);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record OrderFact(
            String requestId,
            Boolean reservationExists,
            Integer reservationStatus,
            Boolean dependencyAvailable,
            Boolean orderExists,
            String orderNo,
            String deductNo,
            Integer orderStatus,
            String latestEvent,
            LocalDateTime latestEventTime,
            LocalDateTime updatedAt
    ) {
    }
}
