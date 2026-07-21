package com.agent.platform.ordercare.incident.scope.model;

import java.time.Instant;

public record IncidentScopeIdentifier(
        String identifierType,
        String value,
        String sourceSystem,
        String sourceType,
        String sourceId,
        Instant observedAt,
        String resolutionSource
) {
}
