package com.agent.platform.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TraceRun(
        String traceId,
        String conversationId,
        String question,
        String status,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        String failureReason,
        long estimatedPromptTokens,
        long estimatedCompletionTokens,
        double estimatedCost,
        List<TraceSpan> spans,
        List<TraceEvent> events,
        List<TraceReplayEvent> replayEvents,
        Map<String, Object> metrics
) {

    public TraceRun {
        spans = spans == null ? List.of() : List.copyOf(spans);
        events = events == null ? List.of() : List.copyOf(events);
        replayEvents = replayEvents == null ? List.of() : List.copyOf(replayEvents);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
