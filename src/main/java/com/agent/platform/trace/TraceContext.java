package com.agent.platform.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceContext {

    private final String traceId;

    private final String conversationId;

    private final String question;

    private final Instant startedAt;

    private final List<TraceEvent> events = new ArrayList<>();

    private final List<TraceSpan> spans = new ArrayList<>();

    private final List<TraceReplayEvent> replayEvents = new ArrayList<>();

    private final Map<String, Object> metrics = new LinkedHashMap<>();

    private String status = "RUNNING";

    private String failureReason = "";

    private long estimatedPromptTokens;

    private long estimatedCompletionTokens;

    private double estimatedCost;

    public TraceContext(String traceId, String conversationId, String question) {
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.question = question;
        this.startedAt = Instant.now();
    }

    private TraceContext(TraceRun run) {
        this.traceId = run.traceId();
        this.conversationId = run.conversationId();
        this.question = run.question();
        this.startedAt = run.startedAt();
        this.spans.addAll(run.spans());
        this.events.addAll(run.events());
        this.replayEvents.addAll(run.replayEvents());
        this.metrics.putAll(run.metrics());
        this.status = run.status();
        this.failureReason = run.failureReason();
        this.estimatedPromptTokens = run.estimatedPromptTokens();
        this.estimatedCompletionTokens = run.estimatedCompletionTokens();
        this.estimatedCost = run.estimatedCost();
    }

    public static TraceContext resume(TraceRun run) {
        if (run == null) {
            throw new IllegalArgumentException("trace run must not be null");
        }
        TraceContext context = new TraceContext(run);
        context.addEvent("trace.resume", "agent run resumed");
        context.addReplayEvent("run.resumed", "Agent run resumed", Map.of(
                "traceId", run.traceId(),
                "previousStatus", run.status()
        ));
        context.markStatus("RUNNING", "");
        return context;
    }

    public String traceId() {
        return traceId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String question() {
        return question;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void addEvent(String stage, String detail) {
        events.add(new TraceEvent(Instant.now(), stage, detail));
    }

    public void addSpan(TraceSpan span) {
        spans.add(span);
    }

    public void addReplayEvent(String eventType, String summary, Map<String, Object> payload) {
        replayEvents.add(new TraceReplayEvent(replayEvents.size() + 1, Instant.now(), eventType, summary, payload));
    }

    public void putMetric(String key, Object value) {
        metrics.put(key, value);
    }

    public void addTokenUsage(long promptTokens, long completionTokens, double cost) {
        estimatedPromptTokens += Math.max(0, promptTokens);
        estimatedCompletionTokens += Math.max(0, completionTokens);
        estimatedCost += Math.max(0, cost);
    }

    public void markStatus(String status, String failureReason) {
        this.status = status == null || status.isBlank() ? this.status : status;
        this.failureReason = failureReason == null ? "" : failureReason;
    }

    public TraceSummary summary() {
        return new TraceSummary(traceId, conversationId, events);
    }

    public TraceRun runSnapshot(Instant endedAt) {
        Instant effectiveEndedAt = endedAt == null ? Instant.now() : endedAt;
        return new TraceRun(
                traceId,
                conversationId,
                question,
                status,
                startedAt,
                effectiveEndedAt,
                Math.max(0, effectiveEndedAt.toEpochMilli() - startedAt.toEpochMilli()),
                failureReason,
                estimatedPromptTokens,
                estimatedCompletionTokens,
                estimatedCost,
                spans,
                events,
                replayEvents,
                metrics
        );
    }
}
