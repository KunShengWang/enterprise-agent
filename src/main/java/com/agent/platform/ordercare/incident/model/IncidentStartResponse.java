package com.agent.platform.ordercare.incident.model;

import java.time.Instant;

public record IncidentStartResponse(
        String incidentId,
        IncidentStatus status,
        Instant createdAt
) {}
