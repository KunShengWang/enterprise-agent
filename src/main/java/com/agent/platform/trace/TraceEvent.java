package com.agent.platform.trace;

import java.time.Instant;

public record TraceEvent(
        Instant occurredAt,
        String stage,
        String detail
) {
}
