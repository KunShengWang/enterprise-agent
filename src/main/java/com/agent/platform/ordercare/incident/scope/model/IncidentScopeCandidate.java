package com.agent.platform.ordercare.incident.scope.model;

import java.util.List;

public record IncidentScopeCandidate(
        String requestId,
        String orderNo,
        String deductNo,
        List<String> deadLetterIds,
        List<String> queueNames,
        Integer orderStatus,
        Integer reservationStatus,
        Integer deductStatus,
        String releaseState,
        List<IncidentScopeAnomalyType> anomalyTypes,
        List<String> inclusionReasons,
        IncidentScopeRelationQuality relationQuality,
        String completeness,
        List<IncidentScopeIdentifier> identifiers,
        List<IncidentScopeSourceReference> provenance
) {
    public IncidentScopeCandidate {
        requestId = blank(requestId);
        orderNo = blank(orderNo);
        deductNo = blank(deductNo);
        deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
        queueNames = queueNames == null ? List.of() : List.copyOf(queueNames);
        releaseState = blank(releaseState);
        anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
        inclusionReasons = inclusionReasons == null ? List.of() : List.copyOf(inclusionReasons);
        relationQuality = relationQuality == null ? IncidentScopeRelationQuality.MISSING : relationQuality;
        completeness = blank(completeness);
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }
}
