package com.agent.platform.ordercare.incident.model;

import java.time.OffsetDateTime;
import java.util.List;

public record IncidentFactEnvelope<T>(
        String schemaVersion,
        String sourceSystem,
        String sourceReference,
        String scopeHash,
        OffsetDateTime observedAt,
        boolean truncated,
        List<String> missingRequestIds,
        T facts
) {
    public IncidentFactEnvelope {
        missingRequestIds = missingRequestIds == null ? List.of() : List.copyOf(missingRequestIds);
    }
}
