package com.agent.platform.ordercare.incident.scope.application;

import java.time.Instant;

public record ResolvedIncidentTimeRange(
        Instant startTime,
        Instant endTime,
        String timezone,
        boolean defaultTimezoneUsed,
        String expression
) {
}
