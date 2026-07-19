package com.agent.platform.ordercare.incident.recovery.model;

import java.time.Instant;

public record RecoveryPlanStartResponse(
        String planId,
        String incidentId,
        RecoveryPlanStatus status,
        Instant createdAt,
        boolean newlyCreated
) {
}
