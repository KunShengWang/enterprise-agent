package com.agent.platform.trace;

import java.time.Instant;
import java.util.Map;

public record TraceSpan(
        String spanId,
        String traceId,
        String parentSpanId,
        String name,
        TraceSpanKind kind,
        TraceSpanStatus status,
        String summary,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        String input,
        String output,
        String error,
        Map<String, Object> attributes
) {

    public TraceSpan {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
