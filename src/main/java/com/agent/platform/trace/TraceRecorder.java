package com.agent.platform.trace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TraceRecorder {

    TraceContext start(String conversationId, String question);

    TraceContext resume(String traceId);

    void record(TraceContext context, String stage, String detail);

    void recordSpan(TraceContext context,
                    String name,
                    TraceSpanKind kind,
                    TraceSpanStatus status,
                    String summary,
                    long durationMs,
                    String input,
                    String output,
                    String error,
                    Map<String, Object> attributes);

    void recordReplay(TraceContext context, String eventType, String summary, Map<String, Object> payload);

    void recordMetric(TraceContext context, String key, Object value);

    void recordTokenUsage(TraceContext context, long promptTokens, long completionTokens, double estimatedCost);

    void markStatus(TraceContext context, String status, String failureReason);

    TraceSummary finish(TraceContext context);

    Optional<TraceRun> findRun(String traceId);

    List<TraceRun> recentRuns(int limit);

    List<TraceReplayEvent> replay(String traceId);

    TraceRunStats stats(int limit);
}
