package com.agent.platform.ordercare.incident.scope.model;

import java.time.Instant;
import java.util.List;

public record IncidentScopeCriteria(
        String timeExpression,
        Instant startTime,
        Instant endTime,
        String timezone,
        boolean defaultTimezoneUsed,
        List<IncidentScopeAnomalyType> anomalyTypes,
        List<String> orderNos,
        List<String> deductNos,
        List<String> deadLetterIds
) {
    public IncidentScopeCriteria {
        timeExpression = timeExpression == null ? "" : timeExpression.trim();
        timezone = timezone == null ? "" : timezone.trim();
        anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
        orderNos = orderNos == null ? List.of() : List.copyOf(orderNos);
        deductNos = deductNos == null ? List.of() : List.copyOf(deductNos);
        deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
    }
}
