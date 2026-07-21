package com.agent.platform.ordercare.incident.scope.model;

import java.time.Instant;

public record IncidentScopeSourceReference(
        String sourceSystem,
        String sourceType,
        String sourceId,
        Instant observedAt
) {
}
