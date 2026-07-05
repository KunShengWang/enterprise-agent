package com.agent.platform.trace;

import java.util.List;

public record TraceSummary(
        String traceId,
        String conversationId,
        List<TraceEvent> events
) {

    public TraceSummary {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
