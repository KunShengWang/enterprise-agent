package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;

import java.util.List;

public record IncidentScopeDiscoveryCommand(
        String discoveryRequestId,
        String conversationId,
        String workItemId,
        String sourceInputId,
        String timeExpression,
        String userTimezone,
        List<IncidentScopeAnomalyType> anomalyTypes,
        List<String> orderNos,
        List<String> deductNos,
        List<String> deadLetterIds,
        String traceId
) {
    public IncidentScopeDiscoveryCommand {
        anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
        orderNos = orderNos == null ? List.of() : List.copyOf(orderNos);
        deductNos = deductNos == null ? List.of() : List.copyOf(deductNos);
        deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
    }
}
